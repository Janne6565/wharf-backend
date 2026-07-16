package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a project's metadata. A {@code null} field is left unchanged; a blank
 * name is rejected (a project must keep a name).
 */
@Schema(name = "UpdateProjectRequest", description = "Patch a project's name and/or description")
public record UpdateProjectRequest(

        @Schema(description = "New name, or null to leave unchanged")
        @Size(max = 100)
        String name,

        @Schema(description = "New description, or null to leave unchanged")
        @Size(max = 500)
        String description
) {
}
