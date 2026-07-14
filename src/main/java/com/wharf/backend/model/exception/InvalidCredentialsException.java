package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Deliberately generic so it cannot be used to probe which emails are registered.
 */
public class InvalidCredentialsException extends BaseException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "Invalid email or authentication key");
    }
}
