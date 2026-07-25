package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RegistrationResponse",
        description = "The account was created but is not usable yet: no tokens are issued and no cookie is set "
                + "until the emailed 6-digit code is submitted to /auth/verify-email.")
public record RegistrationResponse(

        @Schema(description = "The normalized address the verification code was sent to")
        String email,

        @Schema(description = "Always true today; present so the client branches on data rather than on the status code")
        boolean verificationRequired
) {
}
