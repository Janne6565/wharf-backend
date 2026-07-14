package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AuthResponse",
        description = "The account plus a new token pair (register / recovery reset). "
                + "In COOKIE mode the refresh token is set as an httpOnly cookie and omitted from the pair.")
public record AuthResponse(

        UserDto user,

        TokenPair tokens
) {
}
