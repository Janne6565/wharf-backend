package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The invite exists and is addressed to the caller, but has passed its expiry.
 */
public class InviteExpiredException extends BaseException {

    public InviteExpiredException() {
        super(HttpStatus.GONE, "This invite has expired");
    }
}
