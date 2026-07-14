package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "DeviceCodeExchangeRequest", description = "Exchange a pairing code for a TUI session")
public record DeviceCodeExchangeRequest(

        @Schema(description = "The pairing code; dashes and case are normalised", example = "K7PQ-M2XR")
        @NotBlank
        String code,

        @Schema(nullable = true, description = "Optional human-readable name for the device")
        String deviceName
) {
}
