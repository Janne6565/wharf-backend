package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "VaultUpdateResponse", description = "The new version after a successful vault write")
public record VaultUpdateResponse(

        long version,

        Instant updatedAt
) {
}
