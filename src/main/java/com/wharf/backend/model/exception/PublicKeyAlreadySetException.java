package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A public key is already published for the account and the request did not ask to rotate
 * it. Rotating is an explicit, destructive act (it invalidates every wrapped key the member
 * holds), so it must be opted into.
 */
public class PublicKeyAlreadySetException extends BaseException {

    public PublicKeyAlreadySetException() {
        super(HttpStatus.CONFLICT, "A public key is already set; pass rotate=true to replace it");
    }
}
