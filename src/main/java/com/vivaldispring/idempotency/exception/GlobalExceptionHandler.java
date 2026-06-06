package com.vivaldispring.idempotency.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateInFlightException.class)
    public ProblemDetail handleDuplicateInFlight(DuplicateInFlightException ex) {
        var pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "A request with this idempotency key is already being processed"
        );
        pd.setType(URI.create("https://example.com/errors/duplicate-in-flight"));
        pd.setProperty("idempotencyKey", ex.getIdempotencyKey());
        return pd;
    }
}
