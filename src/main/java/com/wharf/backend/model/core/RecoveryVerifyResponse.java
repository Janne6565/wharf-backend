package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RecoveryVerifyResponse",
        description = "The current vault blob, so the browser can decrypt it via the recovery slot")
public record RecoveryVerifyResponse(

        @Schema(description = "Base64-encoded ciphertext vault blob")
        String vault
) {
}
