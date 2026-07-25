package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "User", description = "Public profile of an account")
public record UserDto(

        UUID id,

        String email,

        @Schema(description = "Whether the email address has been proven with a one-time code")
        boolean emailVerified,

        Instant createdAt
) {
}
