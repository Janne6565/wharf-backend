package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "MyInvite", description = "An invite addressed to the calling account")
public record MyInviteResponse(

        UUID id,

        UUID projectId,

        String projectName,

        String invitedByEmail,

        Instant createdAt,

        Instant expiresAt
) {
}
