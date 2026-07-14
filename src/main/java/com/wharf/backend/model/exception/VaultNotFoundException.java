package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class VaultNotFoundException extends BaseException {

    public VaultNotFoundException() {
        super(HttpStatus.NOT_FOUND, "No vault exists for this account");
    }
}
