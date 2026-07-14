package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends BaseException {

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "An account with this email already exists");
    }
}
