package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The supplied public key is not valid base64, or does not decode to exactly the expected
 * X25519 key length. The server checks only the length — never the key's contents.
 */
public class InvalidPublicKeyException extends BaseException {

    public InvalidPublicKeyException(int expectedLength) {
        super(HttpStatus.BAD_REQUEST, "Public key must be exactly " + expectedLength + " bytes");
    }
}
