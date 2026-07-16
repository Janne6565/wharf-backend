package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "ProjectSummary", description = "A project as it appears in the caller's project list")
public record ProjectSummaryResponse(

        UUID id,

        String name,

        String description,

        @Schema(description = "The calling member's role in this project")
        ProjectRole role,

        long memberCount,

        long pendingInviteCount,

        long vaultVersion,

        @Schema(description = "True when the caller holds no wrapped key yet (awaiting an admin to seal the DEK)")
        boolean awaitingKey
) {
}
