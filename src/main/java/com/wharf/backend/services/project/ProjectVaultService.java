package com.wharf.backend.services.project;

import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.ProjectVaultEntity;
import com.wharf.backend.model.action.UpdateVaultRequest;
import com.wharf.backend.model.core.ProjectVaultResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.model.exception.MemberNotKeyedException;
import com.wharf.backend.model.exception.ProjectNotFoundException;
import com.wharf.backend.model.exception.VaultVersionConflictException;
import com.wharf.backend.repository.ProjectVaultRepository;
import com.wharf.backend.services.vault.VaultBlobCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The project's opaque ciphertext vault. Mirrors the personal {@code VaultService}
 * (lock/version/size-guard), but reads are open to any member while writes require a
 * <em>keyed</em> member — one who actually holds a wrapped DEK and can therefore have
 * produced valid ciphertext.
 */
@Service
public class ProjectVaultService {

    private final ProjectVaultRepository projectVaultRepository;
    private final ProjectAccessService access;
    private final VaultBlobCodec blobCodec;

    public ProjectVaultService(ProjectVaultRepository projectVaultRepository,
                               ProjectAccessService access,
                               VaultBlobCodec blobCodec) {
        this.projectVaultRepository = projectVaultRepository;
        this.access = access;
        this.blobCodec = blobCodec;
    }

    @Transactional(readOnly = true)
    public ProjectVaultResponse getVault(UUID projectId, UUID userId) {
        ProjectMemberEntity member = access.requireMember(projectId, userId);
        ProjectVaultEntity vault = projectVaultRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        return new ProjectVaultResponse(
                blobCodec.encode(vault.getBlob()),
                vault.getVersion(),
                vault.getUpdatedAt(),
                ProjectCrypto.encode(member.getWrappedDek()));
    }

    @Transactional
    public VaultUpdateResponse updateVault(UUID projectId, UUID userId, UpdateVaultRequest request) {
        ProjectMemberEntity member = access.requireMember(projectId, userId);
        if (!member.isKeyed()) {
            throw new MemberNotKeyedException();
        }
        byte[] blob = blobCodec.decodeAndValidate(request.vault());
        ProjectVaultEntity vault = projectVaultRepository.findAndLockByProjectId(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        if (vault.getVersion() != request.expectedVersion()) {
            throw new VaultVersionConflictException(request.expectedVersion(), vault.getVersion());
        }

        vault.setBlob(blob);
        vault.setVersion(vault.getVersion() + 1);
        vault.setUpdatedAt(Instant.now());
        return new VaultUpdateResponse(vault.getVersion(), vault.getUpdatedAt());
    }
}
