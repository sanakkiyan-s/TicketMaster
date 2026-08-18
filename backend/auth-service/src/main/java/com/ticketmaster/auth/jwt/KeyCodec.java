package com.ticketmaster.auth.jwt;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Converts RSA keys to and from the base64 DER form stored in Vault.
 *
 * Standard Base64 here, deliberately unlike {@link Base64Url}: this is a
 * storage format read only by this service, not a JOSE field. Mixing the two
 * up is a real hazard, so they are separate classes with the difference stated
 * rather than one class with a flag.
 *
 * PKCS#8 for private keys and X.509/SubjectPublicKeyInfo for public keys —
 * exactly what `openssl genpkey` and `openssl rsa -pubout -outform DER`
 * produce, so a key can be generated and inspected with ordinary tools
 * instead of only by this code.
 */
final class KeyCodec {

    private KeyCodec() {
    }

    static String encodePrivate(java.security.PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    static String encodePublic(java.security.PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    static java.security.interfaces.RSAPrivateKey decodePrivate(String base64) {
        try {
            return (java.security.interfaces.RSAPrivateKey) rsa()
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (InvalidKeySpecException e) {
            // Never include the key material in the message.
            throw new IllegalStateException("stored private key is not valid PKCS#8", e);
        }
    }

    static java.security.interfaces.RSAPublicKey decodePublic(String base64) {
        try {
            return (java.security.interfaces.RSAPublicKey) rsa()
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("stored public key is not valid X.509", e);
        }
    }

    private static KeyFactory rsa() {
        try {
            return KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA unavailable", e);
        }
    }
}
