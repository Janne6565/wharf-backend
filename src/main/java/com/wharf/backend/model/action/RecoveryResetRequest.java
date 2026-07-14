package com.wharf.backend.model.action;

import com.wharf.backend.model.core.TokenMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
        @Size(max = VaultPayloadConstraints.MAX_BASE64_LENGTH)
        String vault,

        @Schema(description = "Token delivery mode; defaults to COOKIE for browsers")
        TokenMode tokenMode
) {
    public TokenMode tokenModeOrDefault() {
        return tokenMode == null ? TokenMode.COOKIE : tokenMode;
    }
}
