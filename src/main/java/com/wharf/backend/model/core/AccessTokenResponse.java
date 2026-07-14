package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AccessTokenResponse", description = "A refreshed access token (and rotated refresh in DIRECT mode)")
public record AccessTokenResponse(

        String accessToken,

        @Schema(nullable = true, description = "Rotated refresh token; present only in DIRECT mode")
        String refreshToken
) {
}
