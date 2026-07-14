package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "RecoveryVerifyRequest", description = "Verify a recovery code and retrieve the vault for re-encryption")
public record RecoveryVerifyRequest(

        @NotBlank @Email
        String email,

        @Schema(description = "base64 HKDF-derived recovery authentication key")
        @NotBlank
        String recoveryAuthKey
) {
}
