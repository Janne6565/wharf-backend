package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "PendingKey", description = "A member awaiting a wrapped key who has published a public key to seal against")
public record PendingKeyDto(

        UUID userId,

        String email,

        @Schema(description = "The member's base64 X25519 public key to seal the DEK against")
        String publicKey
) {
}
