package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "Project", description = "Full project detail: metadata, members and pending invites")
public record ProjectResponse(

        UUID id,

        String name,

        String description,

        @Schema(description = "The calling member's role in this project")
        ProjectRole role,

        Instant createdAt,

        @Schema(description = "Current project vault version")
        long vaultVersion,

        List<ProjectMemberDto> members,

        List<ProjectInviteDto> invites
) {
}
