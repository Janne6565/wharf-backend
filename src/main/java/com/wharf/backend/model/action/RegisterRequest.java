package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "RegisterRequest", description = "Create a new zero-knowledge account")
public record RegisterRequest(

        @NotBlank @Email
        String email,

        @Schema(description = "base64 HKDF-derived authentication key (never the password)")
        @NotBlank
        String authKey,

        @Schema(description = "base64 HKDF-derived recovery authentication key")
        @NotBlank
        String recoveryAuthKey,

        @Schema(description = "Base64-encoded ciphertext vault blob (WHARFV format)")
        @NotBlank
        @Size(max = VaultPayloadConstraints.MAX_BASE64_LENGTH)
        String vault
) {
}
