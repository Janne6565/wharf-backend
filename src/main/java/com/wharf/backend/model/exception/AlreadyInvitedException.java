package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * An unexpired invite for that email already exists on the project. (An expired invite is
 * replaced silently instead of raising this.)
 */
public class AlreadyInvitedException extends BaseException {

    public AlreadyInvitedException() {
        super(HttpStatus.CONFLICT, "That email already has a pending invite to this project");
    }
}
