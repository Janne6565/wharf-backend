package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class UserNotFoundException extends BaseException {

    public UserNotFoundException(UUID userId) {
        super(HttpStatus.NOT_FOUND, "User not found: " + userId);
    }
}
