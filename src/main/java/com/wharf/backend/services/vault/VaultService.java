package com.wharf.backend.services.vault;

import com.wharf.backend.entity.VaultEntity;
import com.wharf.backend.model.action.UpdateVaultRequest;
import com.wharf.backend.model.core.VaultResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.model.exception.VaultNotFoundException;
import com.wharf.backend.model.exception.VaultVersionConflictException;
import com.wharf.backend.repository.VaultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Manages the opaque ciphertext vault blob. The server never parses the blob; it only
 * base64-(de)codes it (via {@link VaultBlobCodec}), enforces a size ceiling and tracks a
 * monotonic version for optimistic concurrency.
 */
@Service
public class VaultService {

    private static final long INITIAL_VERSION = 1L;

    private final VaultRepository vaultRepository;
    private final VaultBlobCodec blobCodec;

    public VaultService(VaultRepository vaultRepository, VaultBlobCodec blobCodec) {
        this.vaultRepository = vaultRepository;
        this.blobCodec = blobCodec;
    }

    @Transactional(readOnly = true)
    public VaultResponse getVault(UUID userId) {
        VaultEntity vault = vaultRepository.findByUserId(userId)
                .orElseThrow(VaultNotFoundException::new);
        return new VaultResponse(blobCodec.encode(vault.getBlob()), vault.getVersion(), vault.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public boolean existsForUser(UUID userId) {
        return vaultRepository.existsById(userId);
    }

    @Transactional(readOnly = true)
    public String getBlobBase64(UUID userId) {
        VaultEntity vault = vaultRepository.findByUserId(userId)
                .orElseThrow(VaultNotFoundException::new);
        return blobCodec.encode(vault.getBlob());
    }

    @Transactional
    public VaultUpdateResponse updateVault(UUID userId, UpdateVaultRequest request) {
        byte[] blob = blobCodec.decodeAndValidate(request.vault());
        VaultEntity vault = vaultRepository.findAndLockByUserId(userId)
                .orElseThrow(VaultNotFoundException::new);

        if (vault.getVersion() != request.expectedVersion()) {
            throw new VaultVersionConflictException(request.expectedVersion(), vault.getVersion());
        }

        vault.setBlob(blob);
        vault.setVersion(vault.getVersion() + 1);
        vault.setUpdatedAt(Instant.now());
        return new VaultUpdateResponse(vault.getVersion(), vault.getUpdatedAt());
    }

    /**
     * Create the first vault for a new account. Must run inside the caller's transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void createInitialVault(UUID userId, String base64Blob) {
        vaultRepository.save(VaultEntity.builder()
                .userId(userId)
                .blob(blobCodec.decodeAndValidate(base64Blob))
                .version(INITIAL_VERSION)
                .updatedAt(Instant.now())
                .build());
    }

    /**
     * Replace the vault blob wholesale during a recovery reset (bumps the version).
     * Must run inside the caller's transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void replaceOnReset(UUID userId, String base64Blob) {
        replaceBlob(userId, base64Blob);
    }

    /**
     * Replace the vault blob during a master-password change (bumps the version and returns
     * it so the caller can report the new version). The blob is the same vault re-encrypted
     * under the new password — only the password unlock slot differs. Must run inside the
     * caller's transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public VaultUpdateResponse replaceOnPasswordChange(UUID userId, String base64Blob) {
        VaultEntity vault = replaceBlob(userId, base64Blob);
        return new VaultUpdateResponse(vault.getVersion(), vault.getUpdatedAt());
    }

    /** Locks the vault row, overwrites the blob and bumps the version + timestamp. */
    private VaultEntity replaceBlob(UUID userId, String base64Blob) {
        VaultEntity vault = vaultRepository.findAndLockByUserId(userId)
                .orElseThrow(VaultNotFoundException::new);
        vault.setBlob(blobCodec.decodeAndValidate(base64Blob));
        vault.setVersion(vault.getVersion() + 1);
        vault.setUpdatedAt(Instant.now());
        return vault;
    }
}
