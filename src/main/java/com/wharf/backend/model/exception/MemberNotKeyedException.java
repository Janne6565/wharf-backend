package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The caller is a project member but has no wrapped DEK yet (awaiting key), so it cannot
 * decrypt — and therefore must not write — the project vault. An admin must seal the DEK
 * to the caller's public key first.
 */
public class MemberNotKeyedException extends BaseException {

    public MemberNotKeyedException() {
        super(HttpStatus.FORBIDDEN, "You do not hold a wrapped key for this project yet");
    }
}
