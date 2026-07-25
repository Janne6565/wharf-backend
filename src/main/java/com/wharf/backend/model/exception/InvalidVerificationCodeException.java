package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Deliberately generic: a wrong code, an expired code, no pending verification at all, an
 * unknown email and a code whose attempt budget is exhausted all produce this exact same
 * response. Distinguishing them would turn the endpoint into an oracle — telling an
 * attacker whether an address is registered, or that a guessed code was "close enough" to
 * still be live.
 */
public class InvalidVerificationCodeException extends BaseException {

    public static final String CODE = "invalid_verification_code";

    public InvalidVerificationCodeException() {
        super(HttpStatus.BAD_REQUEST, "Verification code is invalid or expired", CODE);
    }
}
