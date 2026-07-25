package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when account deletion is attempted without an {@code authKey} on an account that
 * has a master password. Distinct from {@link InvalidCredentialsException} on purpose: the
 * key was not <em>wrong</em>, it was never sent, and the client has to react differently
 * (prompt for the password rather than report a failed attempt). Hence the stable
 * {@code auth_key_required} code.
 */
public class PasswordRequiredForDeletionException extends BaseException {

    public static final String CODE = "auth_key_required";

    public PasswordRequiredForDeletionException() {
        super(HttpStatus.BAD_REQUEST,
                "This account has a password; deleting it requires the current authentication key", CODE);
    }
}
