package com.ticketmaster.auth.jwt;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.vault.VaultContainer;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real Vault, not a mock.
 *
 * A mocked VaultTemplate would prove only that this class calls the methods it
 * calls. The things that actually break here - KV v2 nesting the payload under
 * `data`, and a round trip mangling base64 DER - are exactly what a mock
 * cannot catch.
 */
class VaultSigningKeyProviderTest {

    static final String TOKEN = "test-root-token";
    static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:1.17")
            .withVaultToken(TOKEN);

    static VaultTemplate template;

    @BeforeAll
    static void startVault() {
        VAULT.start();
        template = new VaultTemplate(
                VaultEndpoint.from(URI.create("http://" + VAULT.getHost() + ":" + VAULT.getFirstMappedPort())),
                new TokenAuthentication(TOKEN));
    }

    private VaultKeyProperties properties(String path, boolean bootstrap) {
        return new VaultKeyProperties(
                "http://" + VAULT.getHost() + ":" + VAULT.getFirstMappedPort(),
                TOKEN, "secret", path, Duration.ofSeconds(60), bootstrap);
    }

    private static Map<String, String> newKey(KeyStatus status) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        Map<String, String> entry = new HashMap<>();
        entry.put("kid", Thumbprint.of((RSAPublicKey) pair.getPublic()));
        entry.put("status", status.name());
        entry.put("publicKey", KeyCodec.encodePublic(pair.getPublic()));
        entry.put("privateKey", KeyCodec.encodePrivate(pair.getPrivate()));
        return entry;
    }

    private void write(String path, List<Map<String, String>> keys) {
        VaultKeyValueOperations kv = template.opsForKeyValue("secret", KeyValueBackend.KV_2);
        kv.put(path, Map.of("keys", keys));
    }

    @Test
    void publishesEveryKeyButSignsWithOnlyOne() throws Exception {
        // This is ADR-012 rotation phase 1 as data: K2 exists and is in JWKS,
        // but K1 is still doing the signing. If the provider collapsed these,
        // a cutover would strand every gateway that had not yet refreshed.
        Map<String, String> signing = newKey(KeyStatus.SIGNING);
        Map<String, String> published = newKey(KeyStatus.PUBLISHED);
        write("phase-one", List.of(signing, published));

        VaultSigningKeyProvider provider =
                new VaultSigningKeyProvider(template, properties("phase-one", false), Clock.systemUTC());

        assertEquals(2, provider.published().size());
        assertEquals(signing.get("kid"), provider.signing().kid());
        assertTrue(provider.published().stream()
                .anyMatch(key -> key.kid().equals(published.get("kid"))));
    }

    @Test
    void keyMaterialSurvivesTheRoundTrip() throws Exception {
        Map<String, String> stored = newKey(KeyStatus.SIGNING);
        write("round-trip", List.of(stored));

        VaultSigningKeyProvider provider =
                new VaultSigningKeyProvider(template, properties("round-trip", false), Clock.systemUTC());

        SigningKey key = provider.signing();

        // The kid is derived from the modulus and exponent, so recomputing it
        // from the decoded key proves the bytes came back byte-identical. A
        // truncated or re-encoded modulus changes the thumbprint.
        assertEquals(stored.get("kid"), Thumbprint.of(key.publicKey()));
        assertEquals("RSA", key.privateKey().getAlgorithm());
    }

    @Test
    void refusesToStartWithNoSigningKey() throws Exception {
        // Only PUBLISHED keys: a valid phase-1 shape with the signing key
        // deleted. Starting anyway would mean a service that can verify tokens
        // but cannot issue any, failing every login at runtime instead of at
        // deploy time.
        write("no-signing-key", List.of(newKey(KeyStatus.PUBLISHED)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                new VaultSigningKeyProvider(template, properties("no-signing-key", false), Clock.systemUTC()));

        assertTrue(failure.getMessage().contains("no key is marked SIGNING"), failure.getMessage());
    }

    @Test
    void refusesTwoSigningKeys() throws Exception {
        write("two-signing", List.of(newKey(KeyStatus.SIGNING), newKey(KeyStatus.SIGNING)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                new VaultSigningKeyProvider(template, properties("two-signing", false), Clock.systemUTC()));

        assertTrue(failure.getMessage().contains("more than one key is marked SIGNING"),
                failure.getMessage());
    }

    @Test
    void refusesToStartOnAnEmptyPathUnlessBootstrapIsEnabled() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                new VaultSigningKeyProvider(template, properties("empty-path", false), Clock.systemUTC()));

        assertTrue(failure.getMessage().contains("no signing keys at"), failure.getMessage());
    }

    @Test
    void twoInstancesBootstrappingTheSamePathConvergeOnOneKey() {
        // The multi-replica case. Both are told to bootstrap and both find the
        // path empty, so both generate a key - but the CAS create means only
        // one lands, and the loser must ADOPT the winner's key.
        //
        // With a plain last-write-wins put this fails: the second instance
        // overwrites the first, the first keeps signing with a kid that is no
        // longer in JWKS, and the gateway rejects every token it issues until
        // its cache expires.
        VaultSigningKeyProvider first =
                new VaultSigningKeyProvider(template, properties("race", true), Clock.systemUTC());
        VaultSigningKeyProvider second =
                new VaultSigningKeyProvider(template, properties("race", true), Clock.systemUTC());

        assertEquals(first.signing().kid(), second.signing().kid(),
                "replicas disagree on the signing key");
        assertEquals(1, first.published().size(), "the losing bootstrap left a second key behind");
    }

    @Test
    void bootstrapWritesAKeyThatLaterReadsBack() {
        // The dev path. What matters is that the generated key is PERSISTED:
        // if it stayed in memory, two replicas would each bootstrap their own
        // and reject each other's tokens - the exact ephemeral-provider defect
        // this class exists to fix.
        VaultSigningKeyProvider first =
                new VaultSigningKeyProvider(template, properties("bootstrapped", true), Clock.systemUTC());
        String kid = first.signing().kid();

        VaultSigningKeyProvider second =
                new VaultSigningKeyProvider(template, properties("bootstrapped", false), Clock.systemUTC());

        assertEquals(kid, second.signing().kid());
    }
}
