package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised by every token-issuing path when the account has not proven control of its email
 * address yet. Carries the {@code email_not_verified} code so a client can route straight
 * to the "enter your code" screen instead of showing a generic auth failure.
 */
public class EmailNotVerifiedException extends BaseException {

    public static final String CODE = "email_not_verified";

    public EmailNotVerifiedException() {
        super(HttpStatus.FORBIDDEN, "Email address is not verified", CODE);
    }
}
