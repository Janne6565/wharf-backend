package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A supplied wrapped DEK is not valid base64, or does not decode to either accepted
 * wrapped-DEK length (v1 sealed box, or v2 hybrid). The server checks only the length — never the ciphertext's contents.
 */
public class InvalidWrappedKeyException extends BaseException {

    public InvalidWrappedKeyException(int lengthV1, int lengthV2) {
        super(HttpStatus.BAD_REQUEST,
                "Wrapped key must be exactly " + lengthV1 + " bytes (v1) or " + lengthV2 + " bytes (v2)");
    }
}
