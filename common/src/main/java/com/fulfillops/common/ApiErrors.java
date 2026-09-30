package com.fulfillops.common;

import java.util.NoSuchElementException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain exceptions to RFC 9457 problem responses, so every service
 * answers "not found", "conflict" and "bad input" the same way.
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    // Lost an optimistic-lock race or hit a DB constraint (unique key, stock check):
    // the client's view is stale, so it should re-read and retry.
    @ExceptionHandler({ConcurrencyFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail staleWrite(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Conflicting concurrent update; re-read and retry");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * A peer this service depends on could not be reached. That is the peer's outage, not a bug
     * here, and it is not the caller's fault either — 503 says "the call is fine, try again", where
     * 500 would tell them it was their problem and send them looking in the wrong place.
     */
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail peerUnavailable(ResourceAccessException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "A service this one depends on is not reachable");
    }
}
