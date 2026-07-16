package com.wharf.backend.services.project;

import com.wharf.backend.entity.ProjectEntity;
import com.wharf.backend.entity.ProjectInviteEntity;
import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.MyInviteResponse;
import com.wharf.backend.model.core.PendingKeyDto;
import com.wharf.backend.model.core.ProjectInviteDto;
import com.wharf.backend.model.core.ProjectMemberDto;
import com.wharf.backend.model.core.ProjectResponse;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.core.ProjectSummaryResponse;

import java.util.List;

/**
 * Pure entity-to-DTO mapping for the project domain. Kept out of the entities (SRP) and out
 * of the services so the response shapes are assembled in one place.
 */
public final class ProjectMapper {

    private ProjectMapper() {
    }

    public static ProjectMemberDto memberDto(ProjectMemberEntity member, UserEntity user) {
        return new ProjectMemberDto(
                member.getUserId(),
                user.getEmail(),
                member.getRole(),
                member.isKeyed(),
                ProjectCrypto.encode(user.getPublicKey()));
    }

    public static ProjectInviteDto inviteDto(ProjectInviteEntity invite) {
        return new ProjectInviteDto(invite.getId(), invite.getEmail(), invite.getCreatedAt(), invite.getExpiresAt());
    }

    public static PendingKeyDto pendingKeyDto(ProjectMemberEntity member, UserEntity user) {
        return new PendingKeyDto(member.getUserId(), user.getEmail(), ProjectCrypto.encode(user.getPublicKey()));
    }

    public static MyInviteResponse myInviteResponse(ProjectInviteEntity invite, ProjectEntity project, String inviterEmail) {
        return new MyInviteResponse(
                invite.getId(),
                invite.getProjectId(),
                project.getName(),
                inviterEmail,
                invite.getCreatedAt(),
                invite.getExpiresAt());
    }

    public static ProjectResponse detail(ProjectEntity project,
                                         ProjectRole callerRole,
                                         long vaultVersion,
                                         List<ProjectMemberDto> members,
                                         List<ProjectInviteDto> invites) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                callerRole,
                project.getCreatedAt(),
                vaultVersion,
                members,
                invites);
    }

    public static ProjectSummaryResponse summary(ProjectEntity project,
                                                 ProjectRole callerRole,
                                                 long memberCount,
                                                 long pendingInviteCount,
                                                 long vaultVersion,
                                                 boolean awaitingKey) {
        return new ProjectSummaryResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                callerRole,
                memberCount,
                pendingInviteCount,
                vaultVersion,
                awaitingKey);
    }
}
