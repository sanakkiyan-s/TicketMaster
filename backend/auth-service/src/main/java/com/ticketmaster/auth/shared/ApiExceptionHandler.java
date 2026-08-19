package com.ticketmaster.auth.shared;

import com.ticketmaster.auth.login.InvalidCredentialsException;
import com.ticketmaster.auth.login.TooManyLoginAttemptsException;
import com.ticketmaster.auth.registration.EmailAlreadyRegisteredException;
import com.ticketmaster.auth.token.InvalidRefreshTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * One error shape for the whole service, as RFC 9457 ProblemDetail.
 *
 * Without this, a validation failure returns Spring's default error body
 * while a duplicate email returns an empty one — two shapes from one API.
 * ADR-034 generates the published OpenAPI spec from this code, so an
 * inconsistent error shape becomes an inconsistent contract that clients
 * have to special-case per endpoint.
 *
 * Lives in shared/ rather than in a feature package because it is
 * genuinely cross-cutting: every feature's errors pass through it.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 409, not 400 — the request is well-formed, it conflicts with
     * existing state.
     *
     * The response deliberately carries no detail beyond the status.
     * Confirming "this address is registered" is unavoidable on a signup
     * form (the alternative is silently accepting duplicates), but there
     * is no reason to make enumeration any easier than it already is.
     * Login must close the rest of that surface by answering identically
     * for unknown-user and wrong-password.
     */
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail handleEmailAlreadyRegistered(EmailAlreadyRegisteredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Email already registered");
        return problem;
    }

    /**
     * 401 with NO detail, and identically for unknown-email and wrong-password.
     *
     * The temptation is to be helpful ("no account with that address"). That
     * single kindness converts the login endpoint into an account-enumeration
     * oracle: an attacker feeds a breach list through it and learns which
     * addresses are registered here without guessing a single password.
     * LoginService also equalises the TIMING, since a fast rejection leaks the
     * same fact that a different message would.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Invalid credentials");
        return problem;
    }

    /**
     * 429, safe to distinguish from the 401 above - see
     * TooManyLoginAttemptsException's own javadoc for why this one
     * doesn't reopen the enumeration hole InvalidCredentialsException's
     * identical-401 handling exists to close.
     */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ProblemDetail handleTooManyLoginAttempts(TooManyLoginAttemptsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many login attempts");
        return problem;
    }

    /**
     * One 401 for every refresh failure - unknown, expired, revoked, replayed.
     *
     * The exception carries a reason for the logs; the response carries none.
     * "Reuse detected" would tell an attacker their stolen token was noticed,
     * and "expired" rather than "unknown" would confirm the token was real.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Invalid credentials");
        return problem;
    }

    /**
     * Field-level validation failures, reported per field so a client can
     * highlight the offending input rather than guessing.
     *
     * Only the field name and the constraint message are echoed — never
     * the rejected value, which for a password field would put the
     * plaintext into the response body and every access log between here
     * and the browser.
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
