package com.wharf.backend.model.action;

import com.wharf.backend.model.core.TokenMode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RefreshRequest",
        description = "Optional body for refresh. In COOKIE mode the refresh token is read from the cookie instead.")
public record RefreshRequest(

        @Schema(nullable = true, description = "Refresh token; required in DIRECT mode, ignored if a cookie is present")
        String refreshToken,

        @Schema(nullable = true, description = "Token delivery mode; defaults to COOKIE")
        TokenMode tokenMode
) {
    public TokenMode tokenModeOrDefault() {
        return tokenMode == null ? TokenMode.COOKIE : tokenMode;
    }
}
