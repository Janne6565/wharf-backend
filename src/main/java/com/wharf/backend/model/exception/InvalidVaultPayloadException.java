package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class InvalidVaultPayloadException extends BaseException {

    public InvalidVaultPayloadException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
