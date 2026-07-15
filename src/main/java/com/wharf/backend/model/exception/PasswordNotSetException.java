package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a password change is attempted on an account that has no master password yet
 * (e.g. an OAuth-first account). Such accounts must establish a password via /auth/setup;
 * only an existing password can be rotated here.
 */
public class PasswordNotSetException extends BaseException {

    public PasswordNotSetException() {
        super(HttpStatus.CONFLICT, "No password is set for this account");
    }
}
