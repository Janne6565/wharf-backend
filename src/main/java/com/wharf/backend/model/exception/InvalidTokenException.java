package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class InvalidTokenException extends BaseException {

    public InvalidTokenException(String detail) {
        super(HttpStatus.UNAUTHORIZED, detail);
    }
}
