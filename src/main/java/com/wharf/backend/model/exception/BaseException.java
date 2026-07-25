package com.wharf.backend.model.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Root of all domain exceptions. Carries the HTTP status and a client-safe detail
 * message; handled centrally in {@code GlobalExceptionHandler}.
 *
 * <p>Some failures have to be branched on by three separate clients (web, mobile, TUI).
 * Matching on the human-readable {@code detail} is brittle — the wording is free to
 * change — so those exceptions additionally carry a stable, machine-readable {@code code}
 * which the handler surfaces as a {@code code} property on the Problem Detail. It stays
 * {@code null} for the exceptions where the status alone is enough.
 */
@Getter
public abstract class BaseException extends RuntimeException {

    private final HttpStatus status;
    private final String detail;
    private final String code;

    protected BaseException(HttpStatus status, String detail) {
        this(status, detail, null);
    }

    protected BaseException(HttpStatus status, String detail, String code) {
        super(detail);
        this.status = status;
        this.detail = detail;
        this.code = code;
    }
}
