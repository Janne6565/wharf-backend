package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "ResendVerificationRequest",
        description = "Ask for a fresh verification code. The response is always 202, whether or not the "
                + "address belongs to an account, so it cannot be used to probe registrations.")
public record ResendVerificationRequest(

        @NotBlank @Email
        String email
) {
}
