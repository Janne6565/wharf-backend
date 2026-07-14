package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SessionResponse",
        description = "The account plus tokens for a new session (login / device-code exchange). "
                + "In COOKIE mode the refresh token is set as an httpOnly cookie and omitted here.")
public record SessionResponse(

        UserDto user,

        String accessToken,

        @Schema(nullable = true, description = "Present only in DIRECT mode")
        String refreshToken
) {
}
