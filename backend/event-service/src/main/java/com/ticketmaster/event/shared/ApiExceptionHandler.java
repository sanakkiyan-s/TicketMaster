package com.ticketmaster.event.shared;

import com.ticketmaster.event.artist.ArtistNotFoundException;
import com.ticketmaster.event.event.EventNotFoundException;
import com.ticketmaster.event.session.SessionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * One error shape for the whole service, as RFC 9457 ProblemDetail — see
 * auth-service's shared/ApiExceptionHandler for the full rationale
 * (ADR-037 rule 5, ADR-034 publishes this shape as the OpenAPI contract).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 401, no detail beyond the status. Should be unreachable in
     * production — see MissingOrInvalidTokenException's javadoc.
     */
    @ExceptionHandler(MissingOrInvalidTokenException.class)
    public ProblemDetail handleMissingOrInvalidToken(MissingOrInvalidTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Missing or invalid credentials");
        return problem;
    }

    /**
     * 404, not 403 — see EventNotFoundException's javadoc for why "not
     * yours" and "doesn't exist" must answer identically (ADR-030's
     * ownership check, IDOR-oracle avoidance).
     */
    @ExceptionHandler(EventNotFoundException.class)
    public ProblemDetail handleEventNotFound(EventNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Event not found");
        return problem;
    }

    /** Same 404-not-403 reasoning as events, one level down — see SessionNotFoundException's javadoc. */
    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(SessionNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Session not found");
        return problem;
    }

    /** Plain 404 — artists aren't organizer-owned, no ownership-oracle concern. */
    @ExceptionHandler(ArtistNotFoundException.class)
    public ProblemDetail handleArtistNotFound(ArtistNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Artist not found");
        return problem;
    }

    /**
     * Field-level validation failures, reported per field — see
     * auth-service's ApiExceptionHandler for why only the field name and
     * constraint message are echoed, never the rejected value.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() == null
                                ? "invalid"
                                : fieldError.getDefaultMessage(),
                        (first, second) -> first));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }
}
