package com.ticketmaster.auth.jwt;

import java.math.BigInteger;
import java.util.Base64;

/**
 * base64url without padding — the only encoding JOSE uses (RFC 7515 §2).
 *
 * Not interchangeable with plain Base64: `+` and `/` become `-` and `_`, and
 * `=` padding is dropped. A standard-Base64 `n` value produces a JWKS that
 * parses fine and then fails every signature verification, which is a
 * miserable thing to debug.
 */
final class Base64Url {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Base64Url() {
    }

    static String of(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    /**
     * RFC 7518 §6.3.1 wants the unsigned big-endian magnitude, but
     * BigInteger.toByteArray() is two's complement — so a positive value
     * whose top bit is set gains a leading 0x00 sign byte. It must be
     * stripped, or the modulus reads as 2049 bits and strict clients reject
     * the key.
     */
    static String of(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return of(bytes);
    }
}
