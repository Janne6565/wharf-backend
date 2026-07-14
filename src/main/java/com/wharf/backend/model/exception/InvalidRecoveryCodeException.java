package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class InvalidRecoveryCodeException extends BaseException {

    public InvalidRecoveryCodeException() {
        super(HttpStatus.UNAUTHORIZED, "Invalid recovery code");
    }
}
