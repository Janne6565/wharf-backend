package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Rotate the project DEK: replace the vault (re-encrypted under the new DEK), optionally
 * remove a member, and re-wrap the new DEK for every remaining member. This is the only way
 * to revoke a member's access to future secrets.
 */
@Schema(name = "RotateProjectRequest", description = "Rotate the project DEK and re-wrap it for all remaining members")
public record RotateProjectRequest(

        @Schema(description = "Optional member to remove as part of the rotation")
        UUID removeUserId,

        @Schema(description = "Base64-encoded ciphertext project vault blob, re-encrypted under the new DEK")
        @NotBlank
        @Size(max = VaultPayloadConstraints.MAX_BASE64_LENGTH)
        String vault,

        @Schema(description = "The version the client last saw; the rotation is rejected (409) if it has moved on")
        @PositiveOrZero
        long expectedVersion,

        @Schema(description = "The new DEK wrapped once per remaining member")
        @NotNull
        @Valid
        List<WrappedKeyEntry> wrappedKeys
) {

    @Schema(name = "WrappedKeyEntry", description = "A member's copy of the new DEK, sealed to their public key")
    public record WrappedKeyEntry(

            @Schema(description = "The member the key is wrapped for")
            @NotNull
            UUID userId,

            @Schema(description = "Base64-encoded DEK sealed to that member's public key (exactly 80 bytes)")
            @NotBlank
            String wrappedDek
    ) {
    }
}
