package com.wharf.backend.model.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Root of all domain exceptions. Carries the HTTP status and a client-safe detail
 * message; handled centrally in {@code GlobalExceptionHandler}.
 */
@Getter
public abstract class BaseException extends RuntimeException {

    private final HttpStatus status;
    private final String detail;

    protected BaseException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
        this.detail = detail;
    }
}
