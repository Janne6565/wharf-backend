package com.wharf.backend.services.project;

import com.wharf.backend.configuration.ProjectProperties;
import com.wharf.backend.entity.ProjectEntity;
import com.wharf.backend.entity.ProjectInviteEntity;
import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.ProjectVaultEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.CreateInviteRequest;
import com.wharf.backend.model.core.ProjectInviteDto;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.core.ProjectSummaryResponse;
import com.wharf.backend.model.exception.AlreadyInvitedException;
import com.wharf.backend.model.exception.AlreadyMemberException;
import com.wharf.backend.model.exception.InviteExpiredException;
import com.wharf.backend.model.exception.InviteNotFoundException;
import com.wharf.backend.repository.ProjectInviteRepository;
import com.wharf.backend.repository.ProjectMemberRepository;
import com.wharf.backend.repository.ProjectRepository;
import com.wharf.backend.repository.ProjectVaultRepository;
import com.wharf.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectInviteServiceTest {

    @Mock
    private ProjectInviteRepository inviteRepository;
    @Mock
    private ProjectMemberRepository memberRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectVaultRepository projectVaultRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private InviteNotificationService inviteNotifications;

    private ProjectInviteService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID inviteId = UUID.randomUUID();
    private final String email = "invitee@acme.io";

    @BeforeEach
    void setUp() {
        ProjectAccessService access = new ProjectAccessService(memberRepository);
        service = new ProjectInviteService(inviteRepository, memberRepository, projectRepository,
                projectVaultRepository, userRepository, access, new ProjectProperties(Duration.ofDays(14)),
                inviteNotifications);
    }

    private void stubAdmin() {
        when(memberRepository.findByProjectIdAndUserId(projectId, adminId)).thenReturn(Optional.of(
                ProjectMemberEntity.builder().id(UUID.randomUUID()).projectId(projectId).userId(adminId)
                        .role(ProjectRole.ADMIN).createdAt(Instant.now()).build()));
    }

    private UserEntity invitee() {
        return UserEntity.builder().id(UUID.randomUUID()).email(email).createdAt(Instant.now()).build();
    }

    private ProjectInviteEntity invite(Instant expiresAt) {
        return ProjectInviteEntity.builder().id(inviteId).projectId(projectId).email(email)
                .invitedBy(adminId).createdAt(Instant.now().minusSeconds(60)).expiresAt(expiresAt).build();
    }

    // ---- create -------------------------------------------------------------------------

    @Test
    void createInvite_newEmail_savesInvite() {
        stubAdmin();
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(inviteRepository.findByProjectIdAndEmail(projectId, email)).thenReturn(Optional.empty());
        when(inviteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ProjectInviteDto dto = service.createInvite(projectId, adminId, new CreateInviteRequest("Invitee@Acme.io "));

        assertThat(dto.email()).isEqualTo(email);
        verify(inviteRepository).save(any());
        // Notification is fired for the normalized recipient with the invite's expiry.
        verify(inviteNotifications).notifyInvited(eq(email), any(), any(), eq(dto.expiresAt()));
    }

    @Test
    void createInvite_notificationFailure_doesNotFailInvite() {
        stubAdmin();
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(inviteRepository.findByProjectIdAndEmail(projectId, email)).thenReturn(Optional.empty());
        when(inviteRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new RuntimeException("mail down")).when(inviteNotifications)
                .notifyInvited(any(), any(), any(), any());

        assertThatCode(() -> service.createInvite(projectId, adminId, new CreateInviteRequest(email)))
                .doesNotThrowAnyException();
        verify(inviteRepository).save(any());
    }

    @Test
    void createInvite_alreadyMember_throwsConflict() {
        stubAdmin();
        UserEntity user = invitee();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(memberRepository.existsByProjectIdAndUserId(projectId, user.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.createInvite(projectId, adminId, new CreateInviteRequest(email)))
                .isInstanceOf(AlreadyMemberException.class);
    }

    @Test
    void createInvite_unexpiredExisting_throwsConflict() {
        stubAdmin();
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(inviteRepository.findByProjectIdAndEmail(projectId, email))
                .thenReturn(Optional.of(invite(Instant.now().plusSeconds(3600))));

        assertThatThrownBy(() -> service.createInvite(projectId, adminId, new CreateInviteRequest(email)))
                .isInstanceOf(AlreadyInvitedException.class);
    }

    @Test
    void createInvite_expiredExisting_replacedInPlace() {
        stubAdmin();
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        ProjectInviteEntity expired = invite(Instant.now().minusSeconds(3600));
        when(inviteRepository.findByProjectIdAndEmail(projectId, email)).thenReturn(Optional.of(expired));

        ProjectInviteDto dto = service.createInvite(projectId, adminId, new CreateInviteRequest(email));

        assertThat(dto.id()).isEqualTo(inviteId);
        assertThat(expired.getExpiresAt()).isAfter(Instant.now());
        // Refreshed in place — no new row inserted.
        verify(inviteRepository, never()).save(any());
    }

    // ---- accept / decline ---------------------------------------------------------------

    @Test
    void accept_notAddressedToCaller_throwsNotFound() {
        UserEntity other = UserEntity.builder().id(UUID.randomUUID()).email("someone-else@acme.io").build();
        when(inviteRepository.findById(inviteId)).thenReturn(Optional.of(invite(Instant.now().plusSeconds(3600))));

        assertThatThrownBy(() -> service.accept(other, inviteId))
                .isInstanceOf(InviteNotFoundException.class);
    }

    @Test
    void accept_expired_throwsGone() {
        UserEntity user = invitee();
        when(inviteRepository.findById(inviteId)).thenReturn(Optional.of(invite(Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> service.accept(user, inviteId))
                .isInstanceOf(InviteExpiredException.class);
    }

    @Test
    void accept_happy_createsMemberDeletesInviteReturnsSummary() {
        UserEntity user = invitee();
        ProjectInviteEntity inv = invite(Instant.now().plusSeconds(3600));
        when(inviteRepository.findById(inviteId)).thenReturn(Optional.of(inv));
        when(memberRepository.existsByProjectIdAndUserId(projectId, user.getId())).thenReturn(false);
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProjectMemberEntity created = ProjectMemberEntity.builder().id(UUID.randomUUID()).projectId(projectId)
                .userId(user.getId()).role(ProjectRole.MEMBER).createdAt(Instant.now()).build();
        when(memberRepository.findByProjectIdAndUserId(projectId, user.getId())).thenReturn(Optional.of(created));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                ProjectEntity.builder().id(projectId).name("Acme").createdAt(Instant.now()).build()));
        when(projectVaultRepository.findByProjectId(projectId)).thenReturn(Optional.of(
                ProjectVaultEntity.builder().projectId(projectId).blob(new byte[4]).version(2).updatedAt(Instant.now()).build()));
        when(memberRepository.countByProjectId(projectId)).thenReturn(2L);
        when(inviteRepository.countByProjectId(projectId)).thenReturn(0L);

        ProjectSummaryResponse summary = service.accept(user, inviteId);

        assertThat(summary).satisfies(s -> {
            assertThat(s.role()).isEqualTo(ProjectRole.MEMBER);
            assertThat(s.awaitingKey()).isTrue();
            assertThat(s.vaultVersion()).isEqualTo(2L);
        });
        verify(memberRepository).save(any());
        verify(inviteRepository).delete(inv);
    }

    @Test
    void accept_alreadyMember_doesNotDuplicateButConsumesInvite() {
        UserEntity user = invitee();
        ProjectInviteEntity inv = invite(Instant.now().plusSeconds(3600));
        when(inviteRepository.findById(inviteId)).thenReturn(Optional.of(inv));
        when(memberRepository.existsByProjectIdAndUserId(projectId, user.getId())).thenReturn(true);
        ProjectMemberEntity existing = ProjectMemberEntity.builder().id(UUID.randomUUID()).projectId(projectId)
                .userId(user.getId()).role(ProjectRole.MEMBER).wrappedDek(new byte[80]).createdAt(Instant.now())
                .keyedAt(Instant.now()).build();
        when(memberRepository.findByProjectIdAndUserId(projectId, user.getId())).thenReturn(Optional.of(existing));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                ProjectEntity.builder().id(projectId).name("Acme").createdAt(Instant.now()).build()));
        when(projectVaultRepository.findByProjectId(projectId)).thenReturn(Optional.of(
                ProjectVaultEntity.builder().projectId(projectId).blob(new byte[4]).version(1).updatedAt(Instant.now()).build()));
        when(memberRepository.countByProjectId(projectId)).thenReturn(1L);
        when(inviteRepository.countByProjectId(projectId)).thenReturn(0L);

        ProjectSummaryResponse summary = service.accept(user, inviteId);

        assertThat(summary.awaitingKey()).isFalse();
        verify(memberRepository, never()).save(any());
        verify(inviteRepository).delete(inv);
    }

    @Test
    void decline_deletesInvite() {
        UserEntity user = invitee();
        ProjectInviteEntity inv = invite(Instant.now().plusSeconds(3600));
        when(inviteRepository.findById(inviteId)).thenReturn(Optional.of(inv));

        service.decline(user, inviteId);

        verify(inviteRepository).delete(inv);
    }

    @Test
    void decline_notAddressedToCaller_throwsNotFound() {
        UserEntity other = UserEntity.builder().id(UUID.randomUUID()).email("nope@acme.io").build();
        when(inviteRepository.findById(inviteId)).thenReturn(Optional.of(invite(Instant.now().plusSeconds(3600))));

        assertThatThrownBy(() -> service.decline(other, inviteId))
                .isInstanceOf(InviteNotFoundException.class);
    }
}
