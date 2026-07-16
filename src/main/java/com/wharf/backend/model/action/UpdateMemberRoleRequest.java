package com.wharf.backend.model.action;

import com.wharf.backend.model.core.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "UpdateMemberRoleRequest", description = "Change a member's role")
public record UpdateMemberRoleRequest(

        @Schema(description = "The new role. Setting a member to OWNER transfers ownership and demotes the current owner to ADMIN.")
        @NotNull
        ProjectRole role
) {
}
