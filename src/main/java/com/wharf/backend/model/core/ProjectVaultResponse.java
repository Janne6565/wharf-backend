package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "ProjectVaultResponse", description = "The project's ciphertext vault plus the caller's wrapped DEK")
public record ProjectVaultResponse(

        @Schema(description = "Base64-encoded ciphertext project vault blob")
        String vault,

        long version,

        Instant updatedAt,

        @Schema(description = "The caller's base64 wrapped DEK, or null if they are still awaiting a key")
        String wrappedDek
) {
}
