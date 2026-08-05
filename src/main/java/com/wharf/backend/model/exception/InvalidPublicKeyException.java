package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The supplied public key is not valid base64, or does not decode to either accepted
 * identity-key length (v1 X25519, or v2 hybrid). The server checks only the length — never the key's contents.
 */
public class InvalidPublicKeyException extends BaseException {

    public InvalidPublicKeyException(int lengthV1, int lengthV2) {
        super(HttpStatus.BAD_REQUEST,
                "Public key must be exactly " + lengthV1 + " bytes (v1) or " + lengthV2 + " bytes (v2)");
    }
}
