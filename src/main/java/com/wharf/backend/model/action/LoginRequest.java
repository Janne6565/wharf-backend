package com.wharf.backend.model.action;

import com.wharf.backend.model.core.TokenMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "LoginRequest")
public record LoginRequest(

        @NotBlank @Email
        String email,

        @Schema(description = "base64 HKDF-derived authentication key")
        @NotBlank
        String authKey,

        @Schema(description = "Token delivery mode; defaults to COOKIE for browsers")
        TokenMode tokenMode
) {
    public TokenMode tokenModeOrDefault() {
        return tokenMode == null ? TokenMode.COOKIE : tokenMode;
    }
}
