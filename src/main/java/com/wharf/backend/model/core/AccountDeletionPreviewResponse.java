package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * What deleting the calling account would destroy. Deletion is irreversible <em>and</em>
 * destructive to third parties — every project the account owns is deleted outright, taking
 * its other members' access with it — so the UI must be able to state the damage precisely
 * before asking for confirmation. Read-only; nothing here changes any state.
 */
@Schema(name = "AccountDeletionPreview",
        description = "The collateral of deleting the calling account: the projects it owns (which are deleted "
                + "outright, including for their other members) and the memberships it merely holds elsewhere.")
public record AccountDeletionPreviewResponse(

        @Schema(description = "Projects owned by the caller — each is deleted along with the account")
        List<OwnedProjectImpact> ownedProjects,

        @Schema(description = "Number of projects the caller is only a member of; those projects survive and only "
                + "the caller's membership is removed")
        int otherMemberships
) {

    /**
     * One owned project and the number of <em>other</em> people who would lose access with
     * it (the owner themselves is not counted).
     */
    @Schema(name = "OwnedProjectImpact", description = "An owned project that would be deleted with the account")
    public record OwnedProjectImpact(

            UUID id,

            String name,

            @Schema(description = "Members other than the caller who would lose access when this project is deleted")
            int otherMemberCount
    ) {
    }
}
