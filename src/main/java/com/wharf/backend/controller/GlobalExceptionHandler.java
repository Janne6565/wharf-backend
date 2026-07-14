package com.wharf.backend.controller;

import com.wharf.backend.model.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Central RFC 7807 error mapping. Domain exceptions extend {@link BaseException} and are
 * all handled by a single method; only framework exceptions get their own handlers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log4xx = LoggerFactory.getLogger("log4xx");
    private static final Logger log5xx = LoggerFactory.getLogger("log5xx");

    @ExceptionHandler(BaseException.class)
    public ProblemDetail handleBaseException(BaseException ex) {
        logByStatus(ex.getStatus(), ex.getDetail(), ex);
        return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getDetail());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log4xx.warn("Request validation failed: {}", detail);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                detail.isBlank() ? "Request validation failed" : detail);
    }

    /**
     * A concurrent vault write beat this one under the pessimistic lock — surface it as
     * the same version conflict a stale expectedVersion would produce.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log4xx.warn("Optimistic lock failure: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Vault version conflict: it was modified concurrently");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log5xx.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
    }

    private void logByStatus(HttpStatus status, String detail, Exception ex) {
        if (status.is5xxServerError()) {
            log5xx.error("{} - {}", status, detail, ex);
        } else {
            log4xx.warn("{} - {}", status, detail);
        }
    }
}
