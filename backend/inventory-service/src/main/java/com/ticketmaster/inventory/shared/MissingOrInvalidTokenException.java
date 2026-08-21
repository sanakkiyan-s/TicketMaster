package com.ticketmaster.inventory.shared;

/**
 * Thrown when the incoming request has no usable identity. Maps to 401 in
 * ApiExceptionHandler. Defense-in-depth only — api-gateway (ADR-009) is
 * supposed to reject an invalid/missing token before it reaches this
 * service.
 */
public class MissingOrInvalidTokenException extends RuntimeException {
    public MissingOrInvalidTokenException(String message) {
        super(message);
    }

    public MissingOrInvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
