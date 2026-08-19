package com.ticketmaster.auth.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Generates one RSA key pair in memory at startup. Local development only.
 *
 * Why this exists rather than reading Vault from day one: ADR-010 stores the
 * real key in Vault KV v2, which needs a Vault client, a seeded secret and
 * an auth path. That is a slice of its own, and blocking login on it would
 * be sequencing for its own sake.
 *
 * What it costs, stated plainly so nobody is surprised:
 *
 *   - The key set changes on every restart, so tokens issued before a
 *     restart stop validating after it. Fine for one developer, wrong for
 *     anything shared.
 *   - Two replicas would generate DIFFERENT keys and each publish only its
 *     own, so a token minted by one is rejected by the other. This is
 *     correct at exactly one instance and broken at two — hence the
 *     property gate, so production cannot get here by default.
 *   - It publishes a single key, so ADR-012's four-phase rotation cannot be
 *     exercised against it. Rotation belongs to the Vault implementation.
 */
@Component
@ConditionalOnProperty(name = "auth.jwt.key-source", havingValue = "ephemeral")
class EphemeralSigningKeyProvider implements SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EphemeralSigningKeyProvider.class);

    /** 2048 is the RS256 floor worth shipping; smaller is not defensible. */
    private static final int KEY_SIZE = 2048;

    private final SigningKey key;

    EphemeralSigningKeyProvider() {
        KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            // Unreachable on any JRE: RSA is mandatory in the platform spec.
            throw new IllegalStateException("RSA unavailable", e);
        }
        generator.initialize(KEY_SIZE);
        KeyPair pair = generator.generateKeyPair();

        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        this.key = new SigningKey(
                Thumbprint.of(publicKey),
                publicKey,
                (RSAPrivateKey) pair.getPrivate());

        log.warn("using an EPHEMERAL in-memory signing key (kid={}). tokens will not survive "
                + "a restart and will not validate across replicas. ADR-010 requires Vault KV "
                + "for anything shared.", key.kid());
    }

    @Override
    public SigningKey signing() {
        return key;
    }

    @Override
    public List<SigningKey> published() {
        return List.of(key);
    }

}
