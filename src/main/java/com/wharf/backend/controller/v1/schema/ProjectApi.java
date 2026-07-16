package com.wharf.backend.controller.v1.schema;

import com.wharf.backend.configuration.OpenApiConfig;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.CreateInviteRequest;
import com.wharf.backend.model.action.CreateProjectRequest;
import com.wharf.backend.model.action.RotateProjectRequest;
import com.wharf.backend.model.action.SubmitWrappedKeyRequest;
import com.wharf.backend.model.action.UpdateMemberRoleRequest;
import com.wharf.backend.model.action.UpdateProjectRequest;
import com.wharf.backend.model.action.UpdateVaultRequest;
import com.wharf.backend.model.core.PendingKeyDto;
import com.wharf.backend.model.core.ProjectInviteDto;
import com.wharf.backend.model.core.ProjectResponse;
import com.wharf.backend.model.core.ProjectSummaryResponse;
import com.wharf.backend.model.core.ProjectVaultResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

/**
 * Team-project workspaces. Every project-scoped operation is zero-knowledge: the server
 * stores only ciphertext and metadata. A caller who is not a member of the target project
 * always receives a 404 (never a 403) so project existence is never leaked.
 */
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Zero-knowledge team workspaces (server stores ciphertext + metadata only)")
public interface ProjectApi {

    @PostMapping
    @Operation(operationId = "createProject",
            summary = "Create a project with its initial vault and the owner's wrapped DEK",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "201", description = "Project created")
    @ApiResponse(responseCode = "400", description = "Invalid vault or wrapped key")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "412", description = "Caller has not published a public key")
    ResponseEntity<ProjectResponse> createProject(@AuthenticationPrincipal UserEntity user,
                                                  @Valid @RequestBody CreateProjectRequest request);

    @GetMapping
    @Operation(operationId = "listProjects",
            summary = "List the caller's projects",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Projects returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    ResponseEntity<List<ProjectSummaryResponse>> listProjects(@AuthenticationPrincipal UserEntity user);

    @GetMapping("/{id}")
    @Operation(operationId = "getProject",
            summary = "Get project detail (metadata, members, invites)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Project returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    ResponseEntity<ProjectResponse> getProject(@AuthenticationPrincipal UserEntity user, @PathVariable UUID id);

    @PatchMapping("/{id}")
    @Operation(operationId = "updateProject",
            summary = "Update a project's name and/or description (admin+)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Project updated")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    ResponseEntity<Void> updateProject(@AuthenticationPrincipal UserEntity user,
                                       @PathVariable UUID id,
                                       @Valid @RequestBody UpdateProjectRequest request);

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteProject",
            summary = "Delete a project (owner only)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Project deleted")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not the owner")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    ResponseEntity<Void> deleteProject(@AuthenticationPrincipal UserEntity user, @PathVariable UUID id);

    @GetMapping("/{id}/vault")
    @Operation(operationId = "getProjectVault",
            summary = "Fetch the project vault and the caller's wrapped DEK (any member)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Vault returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    ResponseEntity<ProjectVaultResponse> getProjectVault(@AuthenticationPrincipal UserEntity user,
                                                         @PathVariable UUID id);

    @PutMapping("/{id}/vault")
    @Operation(operationId = "updateProjectVault",
            summary = "Replace the project vault with optimistic concurrency (keyed member)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Vault updated")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller holds no wrapped key yet")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    @ApiResponse(responseCode = "413", description = "Vault blob too large")
    ResponseEntity<VaultUpdateResponse> updateProjectVault(@AuthenticationPrincipal UserEntity user,
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody UpdateVaultRequest request);

    @PostMapping("/{id}/invites")
    @Operation(operationId = "createInvite",
            summary = "Invite an email to the project (admin+)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "201", description = "Invite created")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    @ApiResponse(responseCode = "409", description = "Email is already a member or already invited")
    ResponseEntity<ProjectInviteDto> createInvite(@AuthenticationPrincipal UserEntity user,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody CreateInviteRequest request);

    @DeleteMapping("/{id}/invites/{inviteId}")
    @Operation(operationId = "deleteInvite",
            summary = "Revoke a pending invite (admin+)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Invite revoked")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Project or invite not found")
    ResponseEntity<Void> deleteInvite(@AuthenticationPrincipal UserEntity user,
                                      @PathVariable UUID id,
                                      @PathVariable UUID inviteId);

    @GetMapping("/{id}/pending-keys")
    @Operation(operationId = "getPendingKeys",
            summary = "List members awaiting a key who have published a public key (admin+)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Pending keys returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    ResponseEntity<List<PendingKeyDto>> getPendingKeys(@AuthenticationPrincipal UserEntity user,
                                                       @PathVariable UUID id);

    @PostMapping("/{id}/members/{userId}/key")
    @Operation(operationId = "submitMemberKey",
            summary = "Seal the project DEK to a member's public key (admin+)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Key stored")
    @ApiResponse(responseCode = "400", description = "Wrapped key is not 80 bytes")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Project/caller/target member not found")
    @ApiResponse(responseCode = "409", description = "Vault version has moved on (stale DEK)")
    ResponseEntity<Void> submitMemberKey(@AuthenticationPrincipal UserEntity user,
                                         @PathVariable UUID id,
                                         @PathVariable UUID userId,
                                         @Valid @RequestBody SubmitWrappedKeyRequest request);

    @PostMapping("/{id}/rotate")
    @Operation(operationId = "rotateProject",
            summary = "Rotate the project DEK and re-wrap it for all remaining members (admin+)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "200", description = "Rotated; returns the new vault version")
    @ApiResponse(responseCode = "400", description = "Invalid removal or wrapped key")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin, or an admin removing an admin")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    ResponseEntity<VaultUpdateResponse> rotateProject(@AuthenticationPrincipal UserEntity user,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody RotateProjectRequest request);

    @PatchMapping("/{id}/members/{userId}")
    @Operation(operationId = "updateMemberRole",
            summary = "Change a member's role (owner only; OWNER transfers ownership)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Role updated")
    @ApiResponse(responseCode = "400", description = "Owner cannot demote themselves without a transfer")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not the owner")
    @ApiResponse(responseCode = "404", description = "Project or target member not found")
    ResponseEntity<Void> updateMemberRole(@AuthenticationPrincipal UserEntity user,
                                          @PathVariable UUID id,
                                          @PathVariable UUID userId,
                                          @Valid @RequestBody UpdateMemberRoleRequest request);

    @DeleteMapping("/{id}/members/me")
    @Operation(operationId = "leaveProject",
            summary = "Leave a project (the owner must transfer ownership first)",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Left the project")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "404", description = "Project not found or caller is not a member")
    @ApiResponse(responseCode = "409", description = "Owner cannot leave without transferring ownership")
    ResponseEntity<Void> leaveProject(@AuthenticationPrincipal UserEntity user, @PathVariable UUID id);
}
