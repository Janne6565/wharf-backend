package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when an OAuth identity would auto-link into a local account whose email has
 * never been verified. Such a row may well be a squatter who registered someone else's
 * address and set a password on it; linking would let them co-inhabit the real owner's
 * account. The address has to be verified (or the row cleaned up) first.
 */
public class UnverifiedAccountConflictException extends BaseException {

    public static final String CODE = "unverified_account_conflict";

    public UnverifiedAccountConflictException() {
        super(HttpStatus.CONFLICT,
                "An unverified account already exists for this email address", CODE);
    }
}
