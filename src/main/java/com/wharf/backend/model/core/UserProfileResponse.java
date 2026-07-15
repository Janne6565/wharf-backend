package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The authenticated account's profile, extended with the capability flags the frontend
 * uses to route a user (in particular an OAuth account that has not yet set a master
 * password, recovery key or vault).
 */
@Schema(name = "UserProfile", description = "The authenticated account's profile plus routing capability flags")
public record UserProfileResponse(

        UUID id,

        String email,

        Instant createdAt,

        @Schema(description = "Whether a master-password auth key is set (false for a fresh OAuth account)")
        boolean hasPassword,

        @Schema(description = "Whether a recovery key is set")
        boolean hasRecovery,

        @Schema(description = "Whether an encrypted vault exists for this account")
        boolean hasVault
) {
}
