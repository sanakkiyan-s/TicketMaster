package com.ticketmaster.inventory.shared;

import com.ticketmaster.inventory.seat.SeatNotAvailableException;
import com.ticketmaster.inventory.seat.SeatNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/** One error shape for the whole service, RFC 9457 ProblemDetail (ADR-037 rule 5). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MissingOrInvalidTokenException.class)
    public ProblemDetail handleMissingOrInvalidToken(MissingOrInvalidTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Missing or invalid credentials");
        return problem;
    }

    @ExceptionHandler(SeatNotFoundException.class)
    public ProblemDetail handleSeatNotFound(SeatNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Seat not found");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /** 409 — see SeatNotAvailableException's javadoc: this is ADR-002's expected "lost_race" outcome, not a server error. */
    @ExceptionHandler(SeatNotAvailableException.class)
    public ProblemDetail handleSeatNotAvailable(SeatNotAvailableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Seat not available");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage(),
                        (first, second) -> first));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }
}
