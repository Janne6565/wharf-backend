package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class VaultVersionConflictException extends BaseException {

    public VaultVersionConflictException(long expected, long actual) {
        super(HttpStatus.CONFLICT,
                "Vault version conflict: expected " + expected + " but current is " + actual);
    }
}
