package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "RecoveryInitRequest",
        description = "Set the recovery key for an account that does not have one yet (e.g. after OAuth signup)")
public record RecoveryInitRequest(

        @Schema(description = "base64 HKDF-derived recovery authentication key for the freshly generated recovery code")
        @NotBlank
        String recoveryAuthKey
) {
}
