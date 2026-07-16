package com.wharf.backend.services.project;

import com.wharf.backend.configuration.VaultProperties;
import com.wharf.backend.entity.ProjectEntity;
import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.ProjectVaultEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.CreateProjectRequest;
import com.wharf.backend.model.action.RotateProjectRequest;
import com.wharf.backend.model.action.RotateProjectRequest.WrappedKeyEntry;
import com.wharf.backend.model.action.SubmitWrappedKeyRequest;
import com.wharf.backend.model.action.UpdateMemberRoleRequest;
import com.wharf.backend.model.action.UpdateProjectRequest;
import com.wharf.backend.model.core.ProjectResponse;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.model.exception.InsufficientProjectRoleException;
import com.wharf.backend.model.exception.InvalidProjectOperationException;
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
import com.wharf.backend.services.vault.VaultBlobCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectVaultRepository projectVaultRepository;
    @Mock
    private ProjectMemberRepository memberRepository;
    @Mock
    private ProjectInviteRepository inviteRepository;
    @Mock
    private UserRepository userRepository;

    private ProjectService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Use the real access service + codec so the tests exercise the genuine 404-vs-403
        // matrix and the real blob/size validation.
        ProjectAccessService access = new ProjectAccessService(memberRepository);
        VaultBlobCodec codec = new VaultBlobCodec(new VaultProperties(1_048_576));
        service = new ProjectService(projectRepository, projectVaultRepository, memberRepository,
                inviteRepository, userRepository, access, codec);
    }

    private static String b64(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    private ProjectMemberEntity member(UUID userId, ProjectRole role, boolean keyed) {
        return ProjectMemberEntity.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .userId(userId)
                .role(role)
                .wrappedDek(keyed ? new byte[80] : null)
                .createdAt(Instant.now())
                .keyedAt(keyed ? Instant.now() : null)
                .build();
    }

    private void stubMember(UUID userId, ProjectMemberEntity member) {
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.ofNullable(member));
    }

    // ---- access matrix: 404 (not a member) vs 403 (under-privileged) --------------------

    @Test
    void getDetail_nonMember_throwsNotFound() {
        stubMember(memberId, null);

        assertThatThrownBy(() -> service.getDetail(projectId, memberId))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void updateMetadata_asPlainMember_throwsForbidden() {
        stubMember(memberId, member(memberId, ProjectRole.MEMBER, true));

        assertThatThrownBy(() -> service.updateMetadata(projectId, memberId, new UpdateProjectRequest("x", null)))
                .isInstanceOf(InsufficientProjectRoleException.class);
    }

    @Test
    void deleteProject_asAdmin_throwsForbidden() {
        stubMember(adminId, member(adminId, ProjectRole.ADMIN, true));

        assertThatThrownBy(() -> service.deleteProject(projectId, adminId))
                .isInstanceOf(InsufficientProjectRoleException.class);
    }

    // ---- create -------------------------------------------------------------------------

    @Test
    void create_withoutPublicKey_throwsPreconditionFailed() {
        UserEntity user = UserEntity.builder().id(ownerId).email("a@b.io").publicKey(null).build();

        assertThatThrownBy(() -> service.create(user, new CreateProjectRequest("p", null, b64(64), b64(80))))
                .isInstanceOf(PublicKeyRequiredException.class);
    }

    @Test
    void create_happy_persistsProjectVaultAndOwner() {
        UserEntity user = UserEntity.builder().id(ownerId).email("a@b.io").publicKey(new byte[32]).build();
        when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(projectVaultRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(memberRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ProjectResponse response = service.create(user, new CreateProjectRequest("Acme", "desc", b64(64), b64(80)));

        assertThat(response).satisfies(r -> {
            assertThat(r.name()).isEqualTo("Acme");
            assertThat(r.role()).isEqualTo(ProjectRole.OWNER);
            assertThat(r.vaultVersion()).isEqualTo(1L);
            assertThat(r.members()).singleElement().satisfies(m -> {
                assertThat(m.userId()).isEqualTo(ownerId);
                assertThat(m.keyed()).isTrue();
            });
            assertThat(r.invites()).isEmpty();
        });
        verify(projectVaultRepository).save(any(ProjectVaultEntity.class));
    }

    // ---- rotate -------------------------------------------------------------------------

    private ProjectVaultEntity vault(long version) {
        return ProjectVaultEntity.builder().projectId(projectId).blob(new byte[10])
                .version(version).updatedAt(Instant.now()).build();
    }

    @Test
    void rotate_staleVersion_throwsConflict() {
        stubMember(ownerId, member(ownerId, ProjectRole.OWNER, true));
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(5)));

        RotateProjectRequest req = new RotateProjectRequest(null, b64(64), 2L, List.of());
        assertThatThrownBy(() -> service.rotate(projectId, ownerId, req))
                .isInstanceOf(VaultVersionConflictException.class);
    }

    @Test
    void rotate_removingOwner_throwsBadRequest() {
        ProjectMemberEntity owner = member(ownerId, ProjectRole.OWNER, true);
        ProjectMemberEntity admin = member(adminId, ProjectRole.ADMIN, true);
        stubMember(adminId, admin);
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(3)));
        when(memberRepository.findByProjectId(projectId)).thenReturn(new ArrayList<>(List.of(owner, admin)));

        RotateProjectRequest req = new RotateProjectRequest(ownerId, b64(64), 3L, List.of());
        assertThatThrownBy(() -> service.rotate(projectId, adminId, req))
                .isInstanceOf(InvalidProjectOperationException.class);
    }

    @Test
    void rotate_adminRemovingAdmin_throwsForbidden() {
        ProjectMemberEntity acting = member(adminId, ProjectRole.ADMIN, true);
        UUID otherAdminId = UUID.randomUUID();
        ProjectMemberEntity otherAdmin = member(otherAdminId, ProjectRole.ADMIN, true);
        stubMember(adminId, acting);
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(1)));
        when(memberRepository.findByProjectId(projectId)).thenReturn(new ArrayList<>(List.of(acting, otherAdmin)));

        RotateProjectRequest req = new RotateProjectRequest(otherAdminId, b64(64), 1L, List.of());
        assertThatThrownBy(() -> service.rotate(projectId, adminId, req))
                .isInstanceOf(InsufficientProjectRoleException.class);
    }

    @Test
    void rotate_foreignUserInWrappedKeys_throwsBadRequest() {
        ProjectMemberEntity owner = member(ownerId, ProjectRole.OWNER, true);
        stubMember(ownerId, owner);
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(1)));
        when(memberRepository.findByProjectId(projectId)).thenReturn(new ArrayList<>(List.of(owner)));

        RotateProjectRequest req = new RotateProjectRequest(null, b64(64), 1L,
                List.of(new WrappedKeyEntry(UUID.randomUUID(), b64(80))));
        assertThatThrownBy(() -> service.rotate(projectId, ownerId, req))
                .isInstanceOf(InvalidProjectOperationException.class);
    }

    @Test
    void rotate_nullsAllThenSetsProvided_bumpsVersion() {
        ProjectMemberEntity owner = member(ownerId, ProjectRole.OWNER, true);
        ProjectMemberEntity keptMember = member(memberId, ProjectRole.MEMBER, true);
        stubMember(ownerId, owner);
        ProjectVaultEntity v = vault(4);
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(v));
        when(memberRepository.findByProjectId(projectId)).thenReturn(new ArrayList<>(List.of(owner, keptMember)));

        // Re-key only the owner; the other member re-enters the awaiting-key state.
        RotateProjectRequest req = new RotateProjectRequest(null, b64(64), 4L,
                List.of(new WrappedKeyEntry(ownerId, b64(80))));
        VaultUpdateResponse response = service.rotate(projectId, ownerId, req);

        assertThat(response.version()).isEqualTo(5L);
        assertThat(v.getVersion()).isEqualTo(5L);
        assertThat(owner.isKeyed()).isTrue();
        assertThat(keptMember.isKeyed()).isFalse();
        assertThat(keptMember.getKeyedAt()).isNull();
    }

    // ---- submit member key --------------------------------------------------------------

    @Test
    void submitMemberKey_staleVaultVersion_throwsConflict() {
        stubMember(adminId, member(adminId, ProjectRole.ADMIN, true));
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(7)));

        assertThatThrownBy(() -> service.submitMemberKey(projectId, adminId, memberId,
                new SubmitWrappedKeyRequest(b64(80), 3L)))
                .isInstanceOf(VaultVersionConflictException.class);
    }

    @Test
    void submitMemberKey_targetNotMember_throwsNotFound() {
        stubMember(adminId, member(adminId, ProjectRole.ADMIN, true));
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(2)));
        when(memberRepository.findByProjectIdAndUserId(projectId, memberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitMemberKey(projectId, adminId, memberId,
                new SubmitWrappedKeyRequest(b64(80), 2L)))
                .isInstanceOf(ProjectMemberNotFoundException.class);
    }

    @Test
    void submitMemberKey_happy_setsWrappedKey() {
        stubMember(adminId, member(adminId, ProjectRole.ADMIN, true));
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(2)));
        ProjectMemberEntity target = member(memberId, ProjectRole.MEMBER, false);
        when(memberRepository.findByProjectIdAndUserId(projectId, memberId)).thenReturn(Optional.of(target));

        service.submitMemberKey(projectId, adminId, memberId, new SubmitWrappedKeyRequest(b64(80), 2L));

        assertThat(target.isKeyed()).isTrue();
        assertThat(target.getKeyedAt()).isNotNull();
    }

    @Test
    void submitMemberKey_wrongLength_throwsBadRequest() {
        stubMember(adminId, member(adminId, ProjectRole.ADMIN, true));
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(2)));

        assertThatThrownBy(() -> service.submitMemberKey(projectId, adminId, memberId,
                new SubmitWrappedKeyRequest(b64(79), 2L)))
                .isInstanceOf(com.wharf.backend.model.exception.InvalidWrappedKeyException.class);
    }

    // ---- role change / transfer ---------------------------------------------------------

    @Test
    void updateMemberRole_promoteToOwner_transfersAndDemotesOwner() {
        ProjectMemberEntity owner = member(ownerId, ProjectRole.OWNER, true);
        ProjectMemberEntity target = member(memberId, ProjectRole.MEMBER, true);
        stubMember(ownerId, owner);
        when(memberRepository.findByProjectIdAndUserId(projectId, memberId)).thenReturn(Optional.of(target));

        service.updateMemberRole(projectId, ownerId, memberId, new UpdateMemberRoleRequest(ProjectRole.OWNER));

        assertThat(target.getRole()).isEqualTo(ProjectRole.OWNER);
        assertThat(owner.getRole()).isEqualTo(ProjectRole.ADMIN);
    }

    @Test
    void updateMemberRole_ownerDemotingSelf_throwsBadRequest() {
        ProjectMemberEntity owner = member(ownerId, ProjectRole.OWNER, true);
        stubMember(ownerId, owner);
        when(memberRepository.findByProjectIdAndUserId(projectId, ownerId)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.updateMemberRole(projectId, ownerId, ownerId,
                new UpdateMemberRoleRequest(ProjectRole.ADMIN)))
                .isInstanceOf(InvalidProjectOperationException.class);
        assertThat(owner.getRole()).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    void updateMemberRole_byNonOwner_throwsForbidden() {
        stubMember(adminId, member(adminId, ProjectRole.ADMIN, true));

        assertThatThrownBy(() -> service.updateMemberRole(projectId, adminId, memberId,
                new UpdateMemberRoleRequest(ProjectRole.ADMIN)))
                .isInstanceOf(InsufficientProjectRoleException.class);
    }

    // ---- leave --------------------------------------------------------------------------

    @Test
    void leave_asOwner_throwsConflict() {
        stubMember(ownerId, member(ownerId, ProjectRole.OWNER, true));

        assertThatThrownBy(() -> service.leave(projectId, ownerId))
                .isInstanceOf(OwnerCannotLeaveException.class);
        verify(memberRepository, never()).delete(any());
    }

    @Test
    void leave_asMember_deletesMembership() {
        ProjectMemberEntity m = member(memberId, ProjectRole.MEMBER, true);
        stubMember(memberId, m);

        service.leave(projectId, memberId);

        verify(memberRepository).delete(m);
    }
}
