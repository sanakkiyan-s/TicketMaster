package com.ticketmaster.auth.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultVersionedKeyValueOperations;
import org.springframework.vault.support.VaultResponseSupport;
import org.springframework.vault.support.Versioned;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the signing key set from Vault KV v2 (ADR-010).
 *
 * Private keys are held in memory and never written to disk — ADR-010 records
 * that as an accepted risk rather than leaving it implicit. Transit is
 * deliberately not used: auth-service signs on every login and refresh, and a
 * network round trip per signature on the highest-QPS service is the wrong
 * trade.
 *
 * Unlike the ephemeral provider, this is correct across replicas, because every
 * instance reads the same set. That shared set is also what makes ADR-012's
 * rotation expressible at all.
 */
@Component
@ConditionalOnProperty(name = "auth.jwt.key-source", havingValue = "vault")
class VaultSigningKeyProvider implements SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultSigningKeyProvider.class);
    private static final int KEY_SIZE = 2048;

    private final VaultKeyValueOperations kv;
    private final VaultVersionedKeyValueOperations versionedKv;
    private final VaultKeyProperties properties;
    private final Clock clock;

    private final AtomicReference<CachedKeys> cache = new AtomicReference<>();

    VaultSigningKeyProvider(VaultTemplate vault, VaultKeyProperties properties, Clock clock) {
        this.kv = vault.opsForKeyValue(properties.backend(), KeyValueBackend.KV_2);
        this.versionedKv = vault.opsForVersionedKeyValue(properties.backend());
        this.properties = properties;
        this.clock = clock;

        // Fail fast at startup. A service that starts without a signing key
        // answers every login with a 500, which reads as a bug in login rather
        // than a missing secret. ADR-010: "refuses to start if any required
        // secret absent".
        load();
    }

    private record CachedKeys(List<SigningKey> keys, SigningKey signing, Instant readAt) {
    }

    @Override
    public SigningKey signing() {
        return current().signing();
    }

    @Override
    public List<SigningKey> published() {
        return current().keys();
    }

    /**
     * Re-read on an interval, rather than per call or never.
     *
     * Per call would put a Vault round trip on the login path and make Vault a
     * hard dependency of every request. Never would mean a rotation never
     * reaches a running instance, so phase 2 would sign with a key some
     * replicas do not publish — precisely the outage the phases exist to avoid.
     */
    private CachedKeys current() {
        CachedKeys cached = cache.get();
        if (cached == null
                || Instant.now(clock).isAfter(cached.readAt().plus(properties.refreshInterval()))) {
            try {
                return load();
            } catch (RuntimeException e) {
                if (cached == null) {
                    throw e;
                }
                // Vault being briefly unreachable must not stop token issuance:
                // the keys already in memory are still valid. Serve them, and
                // complain loudly.
                log.warn("could not refresh signing keys from Vault; serving the cached set", e);
                return cached;
            }
        }
        return cached;
    }

    @SuppressWarnings("unchecked")
    private CachedKeys load() {
        VaultResponseSupport<Map> response = kv.get(properties.path(), Map.class);
        Map<String, Object> data = response == null ? null : response.getData();

        List<Map<String, String>> entries = data == null
                ? List.of()
                : (List<Map<String, String>>) data.getOrDefault("keys", List.of());

        if (entries.isEmpty()) {
            if (!properties.bootstrapIfEmpty()) {
                throw new IllegalStateException(
                        "no signing keys at " + properties.backend() + "/" + properties.path()
                                + " - seed one, or set auth.jwt.vault.bootstrap-if-empty=true for local dev");
            }
            bootstrapFirstKey();

            // Re-read unconditionally, including on the path where this
            // instance won the create. With several replicas starting against
            // an empty Vault, exactly one create succeeds; the losers must
            // adopt the winner's key rather than the one they generated, or
            // they would sign with a kid that is not in JWKS and every token
            // they issue would be rejected.
            response = kv.get(properties.path(), Map.class);
            data = response == null ? null : response.getData();
            entries = data == null
                    ? List.of()
                    : (List<Map<String, String>>) data.getOrDefault("keys", List.of());

            if (entries.isEmpty()) {
                throw new IllegalStateException(
                        "bootstrap wrote no key to " + properties.backend() + "/" + properties.path());
            }
        }

        List<SigningKey> keys = new ArrayList<>();
        SigningKey signing = null;

        for (Map<String, String> entry : entries) {
            SigningKey key = new SigningKey(
                    entry.get("kid"),
                    KeyCodec.decodePublic(entry.get("publicKey")),
                    KeyCodec.decodePrivate(entry.get("privateKey")));
            keys.add(key);

            if (KeyStatus.SIGNING.name().equals(entry.get("status"))) {
                if (signing != null) {
                    // Two signing keys means replicas could sign one request
                    // wave with different keys, and the rotation state machine
                    // has lost track of which phase it is in.
                    throw new IllegalStateException("more than one key is marked SIGNING");
                }
                signing = key;
            }
        }

        if (signing == null) {
            throw new IllegalStateException("no key is marked SIGNING");
        }

        log.info("loaded {} signing key(s) from Vault; signing with kid={}",
                keys.size(), signing.kid());
        CachedKeys loaded = new CachedKeys(List.copyOf(keys), signing, Instant.now(clock));
        cache.set(loaded);
        return loaded;
    }

    /**
     * Dev convenience only - see VaultKeyProperties#bootstrapIfEmpty.
     *
     * Writes with CAS 0, which Vault honours as "create only if this key does
     * not already exist". A plain put is last-write-wins: two replicas booting
     * together would each generate a key, the second would overwrite the
     * first, and the first would keep signing with a kid that no longer
     * appears in JWKS until its cache expired - reintroducing exactly the
     * split-key failure this provider exists to prevent.
     *
     * Losing the race is the normal outcome, not an error. The caller re-reads
     * either way.
     */
    private void bootstrapFirstKey() {
        log.warn("no signing key in Vault; generating one because "
                + "auth.jwt.vault.bootstrap-if-empty is enabled. Never set that in production.");

        KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA unavailable", e);
        }
        generator.initialize(KEY_SIZE);
        KeyPair pair = generator.generateKeyPair();

        Map<String, String> entry = new HashMap<>();
        entry.put("kid", Thumbprint.of((RSAPublicKey) pair.getPublic()));
        entry.put("status", KeyStatus.SIGNING.name());
        entry.put("publicKey", KeyCodec.encodePublic(pair.getPublic()));
        entry.put("privateKey", KeyCodec.encodePrivate(pair.getPrivate()));

        try {
            versionedKv.put(properties.path(),
                    Versioned.create(Map.of("keys", List.of(entry)), Versioned.Version.unversioned()));
            log.info("bootstrapped signing key kid={}", entry.get("kid"));
        } catch (VaultException e) {
            // Another instance created the key first. Its key is the real one.
            log.info("another instance bootstrapped the signing key first; adopting it");
        }
    }
}
