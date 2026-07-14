package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * Returned for both expired and already-used codes (HTTP 410) so a caller cannot
 * distinguish which of the two it was.
 */
public class DeviceCodeUnusableException extends BaseException {

    public DeviceCodeUnusableException() {
        super(HttpStatus.GONE, "This device code is no longer valid");
    }
}
