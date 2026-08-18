package com.ticketmaster.auth.jwt;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * One RSA key pair in the signing set, named by its `kid`.
 *
 * ADR-012's rotation is set-based: JWKS publishes several keys at once and
 * every token names which one signed it, so a cutover invalidates nothing.
 * That only works if the kid travels with the key rather than being derived
 * at use time.
 */
record SigningKey(String kid, RSAPublicKey publicKey, RSAPrivateKey privateKey) {
}
