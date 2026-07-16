package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "ProjectInvite", description = "A pending invite on a project")
public record ProjectInviteDto(

        UUID id,

        String email,

        Instant createdAt,

        Instant expiresAt
) {
}
