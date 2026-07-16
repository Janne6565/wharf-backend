package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateProjectRequest", description = "Create a project with its initial encrypted vault and the owner's wrapped DEK")
public record CreateProjectRequest(

        @Schema(description = "Human-readable project name")
        @NotBlank
        @Size(max = 100)
        String name,

        @Schema(description = "Optional project description")
        @Size(max = 500)
        String description,

        @Schema(description = "Base64-encoded ciphertext project vault blob")
        @NotBlank
        @Size(max = VaultPayloadConstraints.MAX_BASE64_LENGTH)
        String vault,

        @Schema(description = "Base64-encoded project DEK sealed to the owner's public key (exactly 80 bytes)")
        @NotBlank
        String wrappedDek
) {
}
