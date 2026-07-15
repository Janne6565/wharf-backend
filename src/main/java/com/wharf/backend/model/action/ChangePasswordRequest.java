package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "ChangePasswordRequest",
        description = "Rotate the master-password auth key and re-encrypt the vault under the new password. "
                + "The recovery code is unchanged, so its slot in the vault blob stays valid.")
public record ChangePasswordRequest(

        @Schema(description = "The current base64 authentication key (proves the caller knows the current password)")
        @NotBlank
        String currentAuthKey,

        @Schema(description = "New base64 authentication key derived from the new password")
        @NotBlank
        String newAuthKey,

        @Schema(description = "Base64-encoded vault blob re-encrypted under the new password (recovery slot unchanged)")
        @NotBlank
        @Size(max = VaultPayloadConstraints.MAX_BASE64_LENGTH)
        String vault
) {
}
