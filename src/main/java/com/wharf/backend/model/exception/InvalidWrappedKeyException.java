package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A supplied wrapped DEK is not valid base64, or does not decode to exactly the expected
 * sealed-box length. The server checks only the length — never the ciphertext's contents.
 */
public class InvalidWrappedKeyException extends BaseException {

    public InvalidWrappedKeyException(int expectedLength) {
        super(HttpStatus.BAD_REQUEST, "Wrapped key must be exactly " + expectedLength + " bytes");
    }
}
