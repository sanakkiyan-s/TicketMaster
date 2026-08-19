package com.ticketmaster.auth.jwt.rotation;

/** Thrown by {@link RotationOrchestrator#startRotation()} while another rotation is already running. */
public class RotationInProgressException extends RuntimeException {

    public RotationInProgressException(String phase) {
        super("a key rotation is already in progress (phase=" + phase + ")");
    }
}
