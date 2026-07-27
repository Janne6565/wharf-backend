package com.wharf.backend.services.project;

import com.wharf.backend.entity.ProjectEntity;
import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.ProjectVaultEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.CreateProjectRequest;
import com.wharf.backend.model.action.RotateProjectRequest;
import com.wharf.backend.model.action.SubmitWrappedKeyRequest;
import com.wharf.backend.model.action.UpdateMemberRoleRequest;
import com.wharf.backend.model.action.UpdateProjectRequest;
import com.wharf.backend.model.core.PendingKeyDto;
import com.wharf.backend.model.core.ProjectInviteDto;
import com.wharf.backend.model.core.ProjectMemberDto;
import com.wharf.backend.model.core.ProjectResponse;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.core.ProjectSummaryResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.model.exception.InvalidProjectOperationException;
import com.wharf.backend.model.exception.InsufficientProjectRoleException;
import com.wharf.backend.model.exception.OwnerCannotLeaveException;
import com.wharf.backend.model.exception.ProjectMemberNotFoundException;
import com.wharf.backend.model.exception.ProjectNotFoundException;
import com.wharf.backend.model.exception.PublicKeyRequiredException;
import com.wharf.backend.model.exception.VaultVersionConflictException;
import com.wharf.backend.repository.ProjectInviteRepository;
import com.wharf.backend.repository.ProjectMemberRepository;
import com.wharf.backend.repository.ProjectRepository;
import com.wharf.backend.repository.ProjectVaultRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.mail.ActorContext;
import com.wharf.backend.services.mail.ActorContextResolver;
import com.wharf.backend.services.vault.VaultBlobCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Team-project lifecycle: creation, listing, detail, metadata edits, deletion, membership
 * and role management, key distribution and DEK rotation. Like the personal vault the server
 * is zero-knowledge — it stores only the opaque project blob, per-member sealed DEKs and
 * server-visible metadata (name/description/emails/roles).
 */
@Service
public class ProjectService {

    private static final long INITIAL_VERSION = 1L;

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final ProjectVaultRepository projectVaultRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final ProjectAccessService access;
    private final VaultBlobCodec blobCodec;
    private final ProjectNotificationService notifications;
    private final ActorContextResolver actorContextResolver;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectVaultRepository projectVaultRepository,
                          ProjectMemberRepository memberRepository,
                          ProjectInviteRepository inviteRepository,
                          UserRepository userRepository,
                          ProjectAccessService access,
                          VaultBlobCodec blobCodec,
                          ProjectNotificationService notifications,
                          ActorContextResolver actorContextResolver) {
        this.projectRepository = projectRepository;
        this.projectVaultRepository = projectVaultRepository;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.blobCodec = blobCodec;
        this.notifications = notifications;
        this.actorContextResolver = actorContextResolver;
    }

    @Transactional
    public ProjectResponse create(UserEntity user, CreateProjectRequest request) {
        if (user.getPublicKey() == null) {
            throw new PublicKeyRequiredException();
        }
        // Validate both crypto inputs (length + size) before any write, so a bad payload
        // aborts with nothing persisted.
        byte[] wrappedDek = ProjectCrypto.decodeWrappedDek(request.wrappedDek());
        byte[] blob = blobCodec.decodeAndValidate(request.vault());

        Instant now = Instant.now();
        ProjectEntity project = projectRepository.save(ProjectEntity.builder()
                .id(UUID.randomUUID())
                .name(request.name())
                .description(request.description())
                .createdAt(now)
                .build());

        projectVaultRepository.save(ProjectVaultEntity.builder()
                .projectId(project.getId())
                .blob(blob)
                .version(INITIAL_VERSION)
                .updatedAt(now)
                .build());

        ProjectMemberEntity owner = memberRepository.save(ProjectMemberEntity.builder()
                .id(UUID.randomUUID())
                .projectId(project.getId())
                .userId(user.getId())
                .role(ProjectRole.OWNER)
                .wrappedDek(wrappedDek)
                .createdAt(now)
                .keyedAt(now)
                .build());

        log.debug("Created project {} owned by {}", project.getId(), user.getId());
        return ProjectMapper.detail(project, owner.getRole(), INITIAL_VERSION,
                List.of(ProjectMapper.memberDto(owner, user)), List.of());
    }

    @Transactional(readOnly = true)
    public List<ProjectSummaryResponse> listForUser(UUID userId) {
        List<ProjectMemberEntity> memberships = memberRepository.findByUserId(userId);
        List<UUID> projectIds = memberships.stream().map(ProjectMemberEntity::getProjectId).toList();
        Map<UUID, ProjectEntity> projects = mapById(projectRepository.findAllById(projectIds), ProjectEntity::getId);
        Map<UUID, ProjectVaultEntity> vaults =
                mapById(projectVaultRepository.findAllById(projectIds), ProjectVaultEntity::getProjectId);

        List<ProjectSummaryResponse> summaries = new ArrayList<>(memberships.size());
        for (ProjectMemberEntity membership : memberships) {
            ProjectEntity project = projects.get(membership.getProjectId());
            ProjectVaultEntity vault = vaults.get(membership.getProjectId());
            summaries.add(ProjectMapper.summary(
                    project,
                    membership.getRole(),
                    memberRepository.countByProjectId(project.getId()),
                    inviteRepository.countByProjectId(project.getId()),
                    vault == null ? 0L : vault.getVersion(),
                    !membership.isKeyed()));
        }
        return summaries;
    }

    @Transactional(readOnly = true)
    public ProjectResponse getDetail(UUID projectId, UUID userId) {
        ProjectMemberEntity caller = access.requireMember(projectId, userId);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        List<ProjectMemberEntity> members = memberRepository.findByProjectId(projectId);
        Map<UUID, UserEntity> users = mapById(
                userRepository.findAllById(members.stream().map(ProjectMemberEntity::getUserId).toList()),
                UserEntity::getId);

        List<ProjectMemberDto> memberDtos = members.stream()
                .map(member -> ProjectMapper.memberDto(member, users.get(member.getUserId())))
                .toList();
        List<ProjectInviteDto> inviteDtos = inviteRepository.findByProjectId(projectId).stream()
                .map(ProjectMapper::inviteDto)
                .toList();
        long vaultVersion = projectVaultRepository.findByProjectId(projectId)
                .map(ProjectVaultEntity::getVersion).orElse(0L);

        return ProjectMapper.detail(project, caller.getRole(), vaultVersion, memberDtos, inviteDtos);
    }

    @Transactional
    public void updateMetadata(UUID projectId, UUID userId, UpdateProjectRequest request) {
        access.requireAdmin(projectId, userId);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new InvalidProjectOperationException("Project name must not be blank");
            }
            project.setName(request.name());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
    }

    @Transactional
    public void deleteProject(UUID projectId, UUID userId) {
        access.requireOwner(projectId, userId);

        // Read the name and the other members before the cascade removes the rows holding them.
        String projectName = projectRepository.findById(projectId).map(ProjectEntity::getName).orElse(null);
        List<String> otherMemberEmails = otherMemberEmails(projectId, userId);

        // Cascades to the vault, members and invites via the ON DELETE CASCADE foreign keys.
        projectRepository.deleteById(projectId);
        log.debug("Deleted project {} by owner {}", projectId, userId);

        if (projectName != null && !otherMemberEmails.isEmpty()) {
            String actorEmail = emailOf(userId);
            ActorContext actor = actorContextResolver.resolve();
            otherMemberEmails.forEach(email ->
                    notifications.notifyProjectDeleted(email, projectName, actorEmail, actor));
        }
    }

    @Transactional(readOnly = true)
    public List<PendingKeyDto> pendingKeys(UUID projectId, UUID userId) {
        access.requireAdmin(projectId, userId);
        List<ProjectMemberEntity> awaiting = memberRepository.findByProjectId(projectId).stream()
                .filter(member -> !member.isKeyed())
                .toList();
        Map<UUID, UserEntity> users = mapById(
                userRepository.findAllById(awaiting.stream().map(ProjectMemberEntity::getUserId).toList()),
                UserEntity::getId);

        return awaiting.stream()
                .map(member -> users.get(member.getUserId()))
                .filter(user -> user != null && user.getPublicKey() != null)
                .map(user -> ProjectMapper.pendingKeyDto(
                        memberOf(awaiting, user.getId()), user))
                .toList();
    }

    @Transactional
    public void submitMemberKey(UUID projectId, UUID actingUserId, UUID targetUserId, SubmitWrappedKeyRequest request) {
        access.requireAdmin(projectId, actingUserId);
        // Lock the vault so this cannot race a concurrent rotation between the version check
        // and the write — the wrapped DEK we store must match the DEK the vault was sealed with.
        ProjectVaultEntity vault = projectVaultRepository.findAndLockByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (vault.getVersion() != request.vaultVersion()) {
            throw new VaultVersionConflictException(request.vaultVersion(), vault.getVersion());
        }

        byte[] wrappedDek = ProjectCrypto.decodeWrappedDek(request.wrappedDek());
        ProjectMemberEntity target = memberRepository.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(() -> new ProjectMemberNotFoundException(targetUserId));
        boolean firstKey = !target.isKeyed();
        target.setWrappedDek(wrappedDek);
        target.setKeyedAt(Instant.now());
        log.debug("Sealed DEK to member {} of project {}", targetUserId, projectId);

        // Only the first sealing is news. Accepting an invite joins a member unkeyed, so this
        // is the moment their access actually starts working — but the same endpoint also
        // re-keys everyone after a rotation, and mailing the whole team on every rotation
        // would train them to ignore the one mail that means something.
        if (firstKey) {
            notifyAccessGranted(projectId, targetUserId, actingUserId, target.getRole());
        }
    }

    private void notifyAccessGranted(UUID projectId, UUID targetUserId, UUID actingUserId, ProjectRole role) {
        String projectName = projectRepository.findById(projectId).map(ProjectEntity::getName).orElse(null);
        String recipient = emailOf(targetUserId);
        if (projectName == null || recipient == null) {
            return;
        }
        notifications.notifyAccessGranted(recipient, projectName, projectId, role, emailOf(actingUserId));
    }

    @Transactional
    public VaultUpdateResponse rotate(UUID projectId, UUID userId, RotateProjectRequest request) {
        ProjectMemberEntity actingMember = access.requireAdmin(projectId, userId);
        ProjectVaultEntity vault = projectVaultRepository.findAndLockByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        if (vault.getVersion() != request.expectedVersion()) {
            throw new VaultVersionConflictException(request.expectedVersion(), vault.getVersion());
        }

        byte[] blob = blobCodec.decodeAndValidate(request.vault());
        List<ProjectMemberEntity> members = memberRepository.findByProjectId(projectId);

        String removedEmail = null;
        if (request.removeUserId() != null) {
            ProjectMemberEntity target = memberOfOrNull(members, request.removeUserId());
            if (target == null) {
                throw new InvalidProjectOperationException("Cannot remove a user who is not a member");
            }
            if (target.getRole() == ProjectRole.OWNER) {
                throw new InvalidProjectOperationException("The owner cannot be removed");
            }
            if (actingMember.getRole() == ProjectRole.ADMIN && target.getRole() == ProjectRole.ADMIN) {
                throw new InsufficientProjectRoleException("Only the owner can remove an admin");
            }
            members.remove(target);
            memberRepository.delete(target);
            removedEmail = emailOf(request.removeUserId());
        }

        // Validate every provided wrapped key up front (target is a remaining member + correct
        // length) so a bad entry aborts the whole rotation before any key is applied.
        Map<UUID, ProjectMemberEntity> remaining = mapById(members, ProjectMemberEntity::getUserId);
        Map<UUID, byte[]> newWraps = new HashMap<>();
        for (RotateProjectRequest.WrappedKeyEntry entry : request.wrappedKeys()) {
            if (!remaining.containsKey(entry.userId())) {
                throw new InvalidProjectOperationException(
                        "Wrapped key references a user who is not a remaining member: " + entry.userId());
            }
            newWraps.put(entry.userId(), ProjectCrypto.decodeWrappedDek(entry.wrappedDek()));
        }

        Instant now = Instant.now();
        vault.setBlob(blob);
        vault.setVersion(vault.getVersion() + 1);
        vault.setUpdatedAt(now);

        // Every remaining member loses their old wrapped DEK; only those given a new one are
        // re-keyed. The rest re-enter the awaiting-key state.
        for (ProjectMemberEntity member : members) {
            byte[] newWrap = newWraps.get(member.getUserId());
            member.setWrappedDek(newWrap);
            member.setKeyedAt(newWrap == null ? null : now);
        }

        log.debug("Rotated project {} DEK to version {} ({} members re-keyed)",
                projectId, vault.getVersion(), newWraps.size());

        // Removal is only expressible as a rotation, so this is the only place a member can
        // learn their access ended. Without it their hosts simply vanish on the next sync.
        if (removedEmail != null) {
            String projectName = projectRepository.findById(projectId).map(ProjectEntity::getName).orElse(null);
            if (projectName != null) {
                notifications.notifyRemoved(removedEmail, projectName, emailOf(userId),
                        actorContextResolver.resolve());
            }
        }
        return new VaultUpdateResponse(vault.getVersion(), vault.getUpdatedAt());
    }

    @Transactional
    public void updateMemberRole(UUID projectId, UUID actingUserId, UUID targetUserId, UpdateMemberRoleRequest request) {
        ProjectMemberEntity owner = access.requireOwner(projectId, actingUserId);
        ProjectMemberEntity target = memberRepository.findByProjectIdAndUserId(projectId, targetUserId)
                .orElseThrow(() -> new ProjectMemberNotFoundException(targetUserId));
        ProjectRole newRole = request.role();

        if (target.getUserId().equals(actingUserId)) {
            // The owner may only "change" their own role by staying owner; a self-demotion must
            // go through an ownership transfer (promoting someone else to OWNER).
            if (newRole != ProjectRole.OWNER) {
                throw new InvalidProjectOperationException(
                        "The owner cannot demote themselves; transfer ownership instead");
            }
            return;
        }

        ProjectRole previousRole = target.getRole();
        if (newRole == ProjectRole.OWNER) {
            // Ownership transfer: promote the target and atomically demote the current owner,
            // preserving the single-owner invariant.
            target.setRole(ProjectRole.OWNER);
            owner.setRole(ProjectRole.ADMIN);
            log.debug("Transferred ownership of project {} from {} to {}", projectId, actingUserId, targetUserId);
            // The outgoing owner is told too: they did initiate it, but losing the ability to
            // delete the project is worth a record they can point at later.
            notifyRoleChanged(projectId, actingUserId, ProjectRole.OWNER, ProjectRole.ADMIN, actingUserId);
        } else {
            target.setRole(newRole);
        }
        if (previousRole != newRole) {
            notifyRoleChanged(projectId, targetUserId, previousRole, newRole, actingUserId);
        }
    }

    private void notifyRoleChanged(UUID projectId, UUID targetUserId,
                                   ProjectRole previousRole, ProjectRole newRole, UUID actingUserId) {
        String projectName = projectRepository.findById(projectId).map(ProjectEntity::getName).orElse(null);
        String recipient = emailOf(targetUserId);
        if (projectName == null || recipient == null) {
            return;
        }
        notifications.notifyRoleChanged(recipient, projectName, projectId, previousRole, newRole,
                emailOf(actingUserId), actorContextResolver.resolve());
    }

    @Transactional
    public void leave(UUID projectId, UUID userId) {
        ProjectMemberEntity member = access.requireMember(projectId, userId);
        if (member.getRole() == ProjectRole.OWNER) {
            throw new OwnerCannotLeaveException();
        }
        memberRepository.delete(member);
        log.debug("Member {} left project {}", userId, projectId);
    }

    private String emailOf(UUID userId) {
        return userRepository.findById(userId).map(UserEntity::getEmail).orElse(null);
    }

    /** Addresses of everyone in the project except {@code excludedUserId}. */
    private List<String> otherMemberEmails(UUID projectId, UUID excludedUserId) {
        List<UUID> ids = memberRepository.findByProjectId(projectId).stream()
                .map(ProjectMemberEntity::getUserId)
                .filter(id -> !id.equals(excludedUserId))
                .toList();
        return ids.isEmpty()
                ? List.of()
                : userRepository.findAllById(ids).stream().map(UserEntity::getEmail).toList();
    }

    private static <T> Map<UUID, T> mapById(List<T> entities, Function<T, UUID> idExtractor) {
        return entities.stream().collect(Collectors.toMap(idExtractor, Function.identity()));
    }

    private static ProjectMemberEntity memberOf(List<ProjectMemberEntity> members, UUID userId) {
        return members.stream().filter(m -> m.getUserId().equals(userId)).findFirst().orElseThrow();
    }

    private static ProjectMemberEntity memberOfOrNull(List<ProjectMemberEntity> members, UUID userId) {
        return members.stream().filter(m -> m.getUserId().equals(userId)).findFirst().orElse(null);
    }
}
