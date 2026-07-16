package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "ProjectMember", description = "A member of a project")
public record ProjectMemberDto(

        UUID userId,

        String email,

        ProjectRole role,

        @Schema(description = "Whether the DEK has been sealed to this member's public key")
        boolean keyed,

        @Schema(description = "The member's base64 X25519 public key, or null if they have not published one")
        String publicKey
) {
}
