package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateInviteRequest", description = "Invite an email address to a project")
public record CreateInviteRequest(

        @Schema(description = "Email address to invite (lowercased server-side)")
        @NotBlank
        @Email
        @Size(max = 320)
        String email
) {
}
