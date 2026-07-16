package com.wharf.backend.controller.v1.implementation;

import com.wharf.backend.controller.v1.schema.ProjectApi;
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
import com.wharf.backend.services.project.ProjectInviteService;
import com.wharf.backend.services.project.ProjectService;
import com.wharf.backend.services.project.ProjectVaultService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ProjectController implements ProjectApi {

    private final ProjectService projectService;
    private final ProjectVaultService projectVaultService;
    private final ProjectInviteService projectInviteService;

    public ProjectController(ProjectService projectService,
                             ProjectVaultService projectVaultService,
                             ProjectInviteService projectInviteService) {
        this.projectService = projectService;
        this.projectVaultService = projectVaultService;
        this.projectInviteService = projectInviteService;
    }

    @Override
    public ResponseEntity<ProjectResponse> createProject(UserEntity user, CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(user, request));
    }

    @Override
    public ResponseEntity<List<ProjectSummaryResponse>> listProjects(UserEntity user) {
        return ResponseEntity.ok(projectService.listForUser(user.getId()));
    }

    @Override
    public ResponseEntity<ProjectResponse> getProject(UserEntity user, UUID id) {
        return ResponseEntity.ok(projectService.getDetail(id, user.getId()));
    }

    @Override
    public ResponseEntity<Void> updateProject(UserEntity user, UUID id, UpdateProjectRequest request) {
        projectService.updateMetadata(id, user.getId(), request);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> deleteProject(UserEntity user, UUID id) {
        projectService.deleteProject(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ProjectVaultResponse> getProjectVault(UserEntity user, UUID id) {
        return ResponseEntity.ok(projectVaultService.getVault(id, user.getId()));
    }

    @Override
    public ResponseEntity<VaultUpdateResponse> updateProjectVault(UserEntity user, UUID id, UpdateVaultRequest request) {
        return ResponseEntity.ok(projectVaultService.updateVault(id, user.getId(), request));
    }

    @Override
    public ResponseEntity<ProjectInviteDto> createInvite(UserEntity user, UUID id, CreateInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectInviteService.createInvite(id, user.getId(), request));
    }

    @Override
    public ResponseEntity<Void> deleteInvite(UserEntity user, UUID id, UUID inviteId) {
        projectInviteService.deleteInvite(id, user.getId(), inviteId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<PendingKeyDto>> getPendingKeys(UserEntity user, UUID id) {
        return ResponseEntity.ok(projectService.pendingKeys(id, user.getId()));
    }

    @Override
    public ResponseEntity<Void> submitMemberKey(UserEntity user, UUID id, UUID userId, SubmitWrappedKeyRequest request) {
        projectService.submitMemberKey(id, user.getId(), userId, request);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<VaultUpdateResponse> rotateProject(UserEntity user, UUID id, RotateProjectRequest request) {
        return ResponseEntity.ok(projectService.rotate(id, user.getId(), request));
    }

    @Override
    public ResponseEntity<Void> updateMemberRole(UserEntity user, UUID id, UUID userId, UpdateMemberRoleRequest request) {
        projectService.updateMemberRole(id, user.getId(), userId, request);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> leaveProject(UserEntity user, UUID id) {
        projectService.leave(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
