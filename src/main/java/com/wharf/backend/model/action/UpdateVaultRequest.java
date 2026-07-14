package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(name = "UpdateVaultRequest", description = "Replace the vault blob with optimistic concurrency")
public record UpdateVaultRequest(

        @Schema(description = "Base64-encoded ciphertext vault blob")
        @NotBlank
        @Size(max = VaultPayloadConstraints.MAX_BASE64_LENGTH)
        String vault,

        @Schema(description = "The version the client last saw; the write is rejected (409) if it has moved on")
        @PositiveOrZero
        long expectedVersion
) {
}
