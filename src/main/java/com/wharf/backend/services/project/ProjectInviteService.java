package com.wharf.backend.services.project;

import com.wharf.backend.configuration.ProjectProperties;
import com.wharf.backend.entity.ProjectEntity;
import com.wharf.backend.entity.ProjectInviteEntity;
import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.ProjectVaultEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.CreateInviteRequest;
import com.wharf.backend.model.core.MyInviteResponse;
import com.wharf.backend.model.core.ProjectInviteDto;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.core.ProjectSummaryResponse;
import com.wharf.backend.model.exception.AlreadyInvitedException;
import com.wharf.backend.model.exception.AlreadyMemberException;
import com.wharf.backend.model.exception.InviteExpiredException;
import com.wharf.backend.model.exception.InviteNotFoundException;
import com.wharf.backend.model.exception.ProjectNotFoundException;
import com.wharf.backend.repository.ProjectInviteRepository;
import com.wharf.backend.repository.ProjectMemberRepository;
import com.wharf.backend.repository.ProjectRepository;
import com.wharf.backend.repository.ProjectVaultRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.auth.EmailNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Project invites: an admin invites an email, the invitee sees and accepts/declines it.
 * There is no email delivery in this milestone — invites are surfaced to the invitee via
 * their own {@code /users/me/invites} listing. An accepted invitee joins as a keyless MEMBER
 * (awaiting an admin to seal the DEK to their public key).
 */
@Service
public class ProjectInviteService {

    private static final Logger log = LoggerFactory.getLogger(ProjectInviteService.class);

    private final ProjectInviteRepository inviteRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectVaultRepository projectVaultRepository;
    private final UserRepository userRepository;
    private final ProjectAccessService access;
    private final ProjectProperties projectProperties;

    public ProjectInviteService(ProjectInviteRepository inviteRepository,
                                ProjectMemberRepository memberRepository,
                                ProjectRepository projectRepository,
                                ProjectVaultRepository projectVaultRepository,
                                UserRepository userRepository,
                                ProjectAccessService access,
                                ProjectProperties projectProperties) {
        this.inviteRepository = inviteRepository;
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
        this.projectVaultRepository = projectVaultRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.projectProperties = projectProperties;
    }

    @Transactional
    public ProjectInviteDto createInvite(UUID projectId, UUID actingUserId, CreateInviteRequest request) {
        access.requireAdmin(projectId, actingUserId);
        String email = EmailNormalizer.normalize(request.email());

        userRepository.findByEmail(email)
                .filter(user -> memberRepository.existsByProjectIdAndUserId(projectId, user.getId()))
                .ifPresent(user -> {
                    throw new AlreadyMemberException();
                });

        Instant now = Instant.now();
        Instant expiresAt = now.plus(projectProperties.inviteTtl());

        // Reuse an existing invite row: an unexpired one is a conflict, an expired one is
        // refreshed in place (avoiding a delete/insert against the unique (project, email)).
        ProjectInviteEntity invite = inviteRepository.findByProjectIdAndEmail(projectId, email)
                .orElse(null);
        if (invite != null) {
            if (!invite.isExpired(now)) {
                throw new AlreadyInvitedException();
            }
            invite.setInvitedBy(actingUserId);
            invite.setCreatedAt(now);
            invite.setExpiresAt(expiresAt);
        } else {
            invite = inviteRepository.save(ProjectInviteEntity.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectId)
                    .email(email)
                    .invitedBy(actingUserId)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .build());
        }

        log.debug("Invited {} to project {}", email, projectId);
        return ProjectMapper.inviteDto(invite);
    }

    @Transactional
    public void deleteInvite(UUID projectId, UUID userId, UUID inviteId) {
        access.requireAdmin(projectId, userId);
        ProjectInviteEntity invite = requireInviteOfProject(projectId, inviteId);
        inviteRepository.delete(invite);
    }

    @Transactional(readOnly = true)
    public List<MyInviteResponse> listMyInvites(UserEntity user) {
        Instant now = Instant.now();
        List<ProjectInviteEntity> invites = inviteRepository.findByEmail(EmailNormalizer.normalize(user.getEmail()))
                .stream()
                .filter(invite -> !invite.isExpired(now))
                .toList();

        Map<UUID, ProjectEntity> projects = mapById(
                projectRepository.findAllById(invites.stream().map(ProjectInviteEntity::getProjectId).toList()),
                ProjectEntity::getId);
        Map<UUID, UserEntity> inviters = mapById(
                userRepository.findAllById(invites.stream().map(ProjectInviteEntity::getInvitedBy).toList()),
                UserEntity::getId);

        return invites.stream()
                .map(invite -> ProjectMapper.myInviteResponse(
                        invite,
                        projects.get(invite.getProjectId()),
                        emailOrNull(inviters.get(invite.getInvitedBy()))))
                .toList();
    }

    @Transactional
    public ProjectSummaryResponse accept(UserEntity user, UUID inviteId) {
        ProjectInviteEntity invite = requireInviteAddressedTo(user, inviteId);
        if (invite.isExpired(Instant.now())) {
            throw new InviteExpiredException();
        }
        UUID projectId = invite.getProjectId();

        // Already a member (e.g. a duplicate/concurrent accept): consume the invite and return
        // the project summary rather than failing.
        if (!memberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            Instant now = Instant.now();
            memberRepository.save(ProjectMemberEntity.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectId)
                    .userId(user.getId())
                    .role(ProjectRole.MEMBER)
                    .wrappedDek(null)
                    .createdAt(now)
                    .keyedAt(null)
                    .build());
            log.debug("User {} accepted invite to project {}", user.getId(), projectId);
        }
        inviteRepository.delete(invite);

        ProjectMemberEntity membership = memberRepository.findByProjectIdAndUserId(projectId, user.getId())
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        return buildSummary(projectId, membership);
    }

    @Transactional
    public void decline(UserEntity user, UUID inviteId) {
        ProjectInviteEntity invite = requireInviteAddressedTo(user, inviteId);
        inviteRepository.delete(invite);
        log.debug("User {} declined invite {}", user.getId(), inviteId);
    }

    private ProjectInviteEntity requireInviteOfProject(UUID projectId, UUID inviteId) {
        return inviteRepository.findById(inviteId)
                .filter(invite -> invite.getProjectId().equals(projectId))
                .orElseThrow(() -> new InviteNotFoundException(inviteId));
    }

    private ProjectInviteEntity requireInviteAddressedTo(UserEntity user, UUID inviteId) {
        String email = EmailNormalizer.normalize(user.getEmail());
        return inviteRepository.findById(inviteId)
                .filter(invite -> invite.getEmail().equalsIgnoreCase(email))
                .orElseThrow(() -> new InviteNotFoundException(inviteId));
    }

    private ProjectSummaryResponse buildSummary(UUID projectId, ProjectMemberEntity membership) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        long vaultVersion = projectVaultRepository.findByProjectId(projectId)
                .map(ProjectVaultEntity::getVersion).orElse(0L);
        return ProjectMapper.summary(
                project,
                membership.getRole(),
                memberRepository.countByProjectId(projectId),
                inviteRepository.countByProjectId(projectId),
                vaultVersion,
                !membership.isKeyed());
    }

    private static String emailOrNull(UserEntity user) {
        return user == null ? null : user.getEmail();
    }

    private static <T> Map<UUID, T> mapById(List<T> entities, Function<T, UUID> idExtractor) {
        return entities.stream().collect(Collectors.toMap(idExtractor, Function.identity()));
    }
}
