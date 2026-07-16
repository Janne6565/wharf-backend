package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The account must publish an X25519 public key before it can take part in a project: the
 * owner's DEK is wrapped against their own public key at creation time, so there is nothing
 * to seal to without one.
 */
public class PublicKeyRequiredException extends BaseException {

    public PublicKeyRequiredException() {
        super(HttpStatus.PRECONDITION_FAILED, "Publish a public key before creating a project");
    }
}
