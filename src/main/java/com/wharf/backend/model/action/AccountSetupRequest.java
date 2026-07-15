package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "AccountSetupRequest",
        description = "One-time onboarding for an account created via OAuth: atomically sets the recovery key, "
                + "the initial encrypted vault, and (optionally) the password auth key derived from the same "
                + "master password the vault was encrypted with")
public record AccountSetupRequest(

        @Schema(description = "base64 HKDF-derived recovery authentication key for the freshly generated recovery code")
        @NotBlank
        String recoveryAuthKey,

        @Schema(description = "Base64-encoded initial ciphertext vault blob (WHARFV format)")
        @NotBlank
        @Size(max = VaultPayloadConstraints.MAX_BASE64_LENGTH)
        String vault,

        @Schema(nullable = true,
                description = "Optional base64 HKDF-derived authentication key; when present the account also "
                        + "gains password login (rejected if a password is already set)")
        String authKey
) {
    /** The optional password auth key, treating blank as absent. */
    public boolean hasAuthKey() {
        return authKey != null && !authKey.isBlank();
    }
}
