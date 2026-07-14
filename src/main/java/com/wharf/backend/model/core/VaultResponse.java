package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "VaultResponse", description = "The stored ciphertext vault and its version")
public record VaultResponse(

        @Schema(description = "Base64-encoded ciphertext vault blob")
        String vault,

        long version,

        Instant updatedAt
) {
}
