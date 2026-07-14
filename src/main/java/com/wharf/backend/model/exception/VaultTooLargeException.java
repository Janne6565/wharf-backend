package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

public class VaultTooLargeException extends BaseException {

    public VaultTooLargeException(long maxSizeBytes) {
        super(HttpStatus.PAYLOAD_TOO_LARGE,
                "Vault blob exceeds the maximum allowed size of " + maxSizeBytes + " bytes");
    }
}
