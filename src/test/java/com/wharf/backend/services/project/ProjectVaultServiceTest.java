package com.wharf.backend.services.project;

import com.wharf.backend.configuration.VaultProperties;
import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.ProjectVaultEntity;
import com.wharf.backend.model.action.UpdateVaultRequest;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.core.ProjectVaultResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.model.exception.MemberNotKeyedException;
import com.wharf.backend.model.exception.ProjectNotFoundException;
import com.wharf.backend.model.exception.VaultVersionConflictException;
import com.wharf.backend.repository.ProjectMemberRepository;
import com.wharf.backend.repository.ProjectVaultRepository;
import com.wharf.backend.services.vault.VaultBlobCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectVaultServiceTest {

    @Mock
    private ProjectVaultRepository projectVaultRepository;
    @Mock
    private ProjectMemberRepository memberRepository;

    private ProjectVaultService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ProjectAccessService access = new ProjectAccessService(memberRepository);
        service = new ProjectVaultService(projectVaultRepository, access, new VaultBlobCodec(new VaultProperties(1_048_576)));
    }

    private static String b64(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    private ProjectMemberEntity member(boolean keyed) {
        return ProjectMemberEntity.builder()
                .id(UUID.randomUUID()).projectId(projectId).userId(userId).role(ProjectRole.MEMBER)
                .wrappedDek(keyed ? new byte[80] : null)
                .createdAt(Instant.now()).keyedAt(keyed ? Instant.now() : null)
                .build();
    }

    private ProjectVaultEntity vault(long version) {
        return ProjectVaultEntity.builder().projectId(projectId).blob(new byte[8])
                .version(version).updatedAt(Instant.now()).build();
    }

    @Test
    void getVault_nonMember_throwsNotFound() {
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVault(projectId, userId))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void getVault_member_returnsCallersWrappedDek() {
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(member(true)));
        when(projectVaultRepository.findByProjectId(projectId)).thenReturn(Optional.of(vault(3)));

        ProjectVaultResponse response = service.getVault(projectId, userId);

        assertThat(response.version()).isEqualTo(3L);
        assertThat(response.wrappedDek()).isNotNull();
    }

    @Test
    void getVault_awaitingKeyMember_wrappedDekIsNull() {
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(member(false)));
        when(projectVaultRepository.findByProjectId(projectId)).thenReturn(Optional.of(vault(1)));

        assertThat(service.getVault(projectId, userId).wrappedDek()).isNull();
    }

    @Test
    void updateVault_unkeyedMember_throwsForbidden() {
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(member(false)));

        assertThatThrownBy(() -> service.updateVault(projectId, userId, new UpdateVaultRequest(b64(64), 1L)))
                .isInstanceOf(MemberNotKeyedException.class);
    }

    @Test
    void updateVault_staleVersion_throwsConflict() {
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(member(true)));
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(vault(5)));

        assertThatThrownBy(() -> service.updateVault(projectId, userId, new UpdateVaultRequest(b64(64), 2L)))
                .isInstanceOf(VaultVersionConflictException.class);
    }

    @Test
    void updateVault_keyedMemberMatchingVersion_bumpsVersion() {
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(member(true)));
        ProjectVaultEntity v = vault(2);
        when(projectVaultRepository.findAndLockByProjectId(projectId)).thenReturn(Optional.of(v));

        VaultUpdateResponse response = service.updateVault(projectId, userId, new UpdateVaultRequest(b64(64), 2L));

        assertThat(response.version()).isEqualTo(3L);
        assertThat(v.getVersion()).isEqualTo(3L);
    }
}
