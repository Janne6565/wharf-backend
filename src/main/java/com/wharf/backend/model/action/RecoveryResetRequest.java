package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "RecoveryResetRequest",
        description = "Re-encrypt the vault under a new password and rotate the recovery code")
public record RecoveryResetRequest(

        @NotBlank @Email
        String email,

        @Schema(description = "The current recovery authentication key (proves ownership)")
        @NotBlank
        String recoveryAuthKey,

        @Schema(description = "New base64 authentication key derived from the new password")
        @NotBlank
        String newAuthKey,

        @Schema(description = "New base64 recovery authentication key for the freshly issued recovery code")
        @NotBlank
        String newRecoveryAuthKey,

        @Schema(description = "Base64-encoded vault blob re-encrypted with the new key material")
        @NotBlank
        String vault
) {
}
