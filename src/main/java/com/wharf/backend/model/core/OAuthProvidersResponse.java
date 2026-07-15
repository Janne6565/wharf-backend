package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "OAuthProvidersResponse",
        description = "The OAuth providers that are currently enabled (configured with a client id + secret)")
public record OAuthProvidersResponse(

        @Schema(description = "Enabled provider slugs, e.g. [\"google\",\"github\"]; empty when none are configured")
        List<String> providers
) {
}
