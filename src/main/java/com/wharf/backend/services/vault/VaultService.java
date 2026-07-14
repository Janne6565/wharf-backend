package com.wharf.backend.services.vault;

import com.wharf.backend.configuration.VaultProperties;
import com.wharf.backend.entity.VaultEntity;
import com.wharf.backend.model.action.UpdateVaultRequest;
import com.wharf.backend.model.core.VaultResponse;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.model.exception.InvalidVaultPayloadException;
import com.wharf.backend.model.exception.VaultNotFoundException;
import com.wharf.backend.model.exception.VaultTooLargeException;
import com.wharf.backend.model.exception.VaultVersionConflictException;
import com.wharf.backend.repository.VaultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Manages the opaque ciphertext vault blob. The server never parses the blob; it only
 * base64-(de)codes it, enforces a size ceiling and tracks a monotonic version for
 * optimistic concurrency.
 */
@Service
public class VaultService {

    private static final long INITIAL_VERSION = 1L;

    private final VaultRepository vaultRepository;
    private final VaultProperties vaultProperties;

    public VaultService(VaultRepository vaultRepository, VaultProperties vaultProperties) {
        this.vaultRepository = vaultRepository;
        this.vaultProperties = vaultProperties;
    }

    @Transactional(readOnly = true)
    public VaultResponse getVault(UUID userId) {
        VaultEntity vault = vaultRepository.findByUserId(userId)
                .orElseThrow(VaultNotFoundException::new);
        return new VaultResponse(encode(vault.getBlob()), vault.getVersion(), vault.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public String getBlobBase64(UUID userId) {
        VaultEntity vault = vaultRepository.findByUserId(userId)
                .orElseThrow(VaultNotFoundException::new);
        return encode(vault.getBlob());
    }

    @Transactional
    public VaultUpdateResponse updateVault(UUID userId, UpdateVaultRequest request) {
        byte[] blob = decodeAndValidate(request.vault());
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
                .blob(decodeAndValidate(base64Blob))
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
        VaultEntity vault = vaultRepository.findAndLockByUserId(userId)
                .orElseThrow(VaultNotFoundException::new);
        vault.setBlob(decodeAndValidate(base64Blob));
        vault.setVersion(vault.getVersion() + 1);
        vault.setUpdatedAt(Instant.now());
    }

    private byte[] decodeAndValidate(String base64Blob) {
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(base64Blob);
        } catch (IllegalArgumentException ex) {
            throw new InvalidVaultPayloadException("Vault blob is not valid base64");
        }
        if (blob.length == 0) {
            throw new InvalidVaultPayloadException("Vault blob must not be empty");
        }
        if (blob.length > vaultProperties.maxSizeBytes()) {
            throw new VaultTooLargeException(vaultProperties.maxSizeBytes());
        }
        return blob;
    }

    private String encode(byte[] blob) {
        return Base64.getEncoder().encodeToString(blob);
    }
}
