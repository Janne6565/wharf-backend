package com.wharf.backend.model.action;

import com.wharf.backend.model.core.TokenMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(name = "VerifyEmailRequest", description = "Complete registration with the emailed 6-digit code")
public record VerifyEmailRequest(

        @NotBlank @Email
        String email,

        @Schema(description = "The 6-digit code from the verification email")
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "must be 6 digits")
        String code,

        @Schema(description = "Token delivery mode; defaults to COOKIE for browsers")
        TokenMode tokenMode
) {
    public TokenMode tokenModeOrDefault() {
        return tokenMode == null ? TokenMode.COOKIE : tokenMode;
    }
}
