package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(name = "SubmitWrappedKeyRequest", description = "Seal the project DEK to a member's public key")
public record SubmitWrappedKeyRequest(

        @Schema(description = "Base64-encoded project DEK sealed to the target member's public key (exactly 80 bytes)")
        @NotBlank
        String wrappedDek,

        @Schema(description = "The project vault version the DEK was wrapped against; rejected (409) if the vault has "
                + "since rotated, so a stale DEK is never accepted")
        @PositiveOrZero
        long vaultVersion
) {
}
