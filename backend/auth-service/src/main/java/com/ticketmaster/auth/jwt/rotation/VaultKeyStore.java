package com.ticketmaster.auth.jwt.rotation;

import com.ticketmaster.auth.jwt.KeyCodec;
import com.ticketmaster.auth.jwt.KeyStatus;
import com.ticketmaster.auth.jwt.Thumbprint;
import com.ticketmaster.auth.jwt.VaultKeyProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponseSupport;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and rewrites the full Vault KV v2 key-set entry that {@link
 * com.ticketmaster.auth.jwt.VaultSigningKeyProvider} reads from - the same
 * `{"keys": [...]}` shape, same field names, same {@link KeyCodec} encoding.
 *
 * Only present when {@code auth.jwt.key-source=vault}, same gate as {@link
 * com.ticketmaster.auth.jwt.VaultConfig}'s {@code VaultTemplate} bean, so a
 * service running the ephemeral provider never needs Vault reachable at all.
 * {@link RotationOrchestrator} treats its absence as "rotation unsupported"
 * rather than failing to start.
 */
@Component
@ConditionalOnProperty(name = "auth.jwt.key-source", havingValue = "vault")
class VaultKeyStore {

    private static final int KEY_SIZE = 2048;

    private final VaultKeyValueOperations kv;
    private final VaultKeyProperties properties;

    VaultKeyStore(VaultTemplate vault, VaultKeyProperties properties) {
        this.kv = vault.opsForKeyValue(properties.backend(), KeyValueBackend.KV_2);
        this.properties = properties;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, String>> readEntries() {
        VaultResponseSupport<Map> response = kv.get(properties.path(), Map.class);
        Map<String, Object> data = response == null ? null : response.getData();
        List<Map<String, String>> entries = data == null
                ? List.of()
                : (List<Map<String, String>>) data.getOrDefault("keys", List.of());
        return new ArrayList<>(entries);
    }

    void writeEntries(List<Map<String, String>> entries) {
        kv.put(properties.path(), Map.of("keys", entries));
    }

    /** A fresh RSA key pair in {@link KeyCodec}'s storage encoding, not yet written anywhere. */
    Map<String, String> generateKey(KeyStatus status) {
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
        entry.put("status", status.name());
        entry.put("publicKey", KeyCodec.encodePublic(pair.getPublic()));
        entry.put("privateKey", KeyCodec.encodePrivate(pair.getPrivate()));
        return entry;
    }
}
