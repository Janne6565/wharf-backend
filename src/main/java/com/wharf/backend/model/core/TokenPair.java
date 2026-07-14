package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TokenPair", description = "A freshly issued identity + refresh token pair")
public record TokenPair(

        @Schema(description = "Short-lived identity (access) token; send as Bearer")
        String accessToken,

        @Schema(description = "Long-lived refresh token")
        String refreshToken
) {
}
