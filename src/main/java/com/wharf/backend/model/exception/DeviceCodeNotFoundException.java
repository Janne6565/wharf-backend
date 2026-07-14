package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class DeviceCodeNotFoundException extends BaseException {

    public DeviceCodeNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Unknown device code");
    }
}
