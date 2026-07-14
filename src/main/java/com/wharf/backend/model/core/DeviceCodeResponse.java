package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "DeviceCodeResponse", description = "A freshly issued device-pairing code")
public record DeviceCodeResponse(

        @Schema(description = "Raw 8-character pairing code (display as XXXX-XXXX)", example = "K7PQM2XR")
        String code,

        Instant expiresAt
) {
}
