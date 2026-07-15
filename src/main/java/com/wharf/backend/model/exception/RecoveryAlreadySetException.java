package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when initialising a recovery key for an account that already has one. Recovery
 * initialisation is first-time-only; rotating an existing recovery key stays exclusive to
 * the recover/reset flow.
 */
public class RecoveryAlreadySetException extends BaseException {

    public RecoveryAlreadySetException() {
        super(HttpStatus.CONFLICT, "A recovery key is already set for this account");
    }
}
