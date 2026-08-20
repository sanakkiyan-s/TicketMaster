package com.ticketmaster.auth.jwt.rotation;

/**
 * Thrown when a rotation action is requested but {@code auth.jwt.key-source}
 * is not {@code vault}. The ephemeral provider publishes exactly one
 * in-memory key with nothing to rotate against - there is no meaningful
 * "no-op" here, only a clear refusal, so a caller does not mistake silence
 * for a rotation that actually happened.
 */
public class RotationNotSupportedException extends RuntimeException {

    public RotationNotSupportedException() {
        super("key rotation requires auth.jwt.key-source=vault");
    }
}
