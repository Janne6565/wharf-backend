package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when account setup is attempted on an account that is already (partially) set up:
 * a recovery key or vault already exists, or a password auth key was supplied while one is
 * already set. Setup is strictly first-time; rotation stays exclusive to recover/reset.
 */
public class AccountSetupConflictException extends BaseException {

    public AccountSetupConflictException(String detail) {
        super(HttpStatus.CONFLICT, detail);
    }
}
