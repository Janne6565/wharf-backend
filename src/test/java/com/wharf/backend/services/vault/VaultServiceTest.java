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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultServiceTest {

    private static final long MAX_SIZE = 1024;

    @Mock
    private VaultRepository vaultRepository;

    private VaultService vaultService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vaultService = new VaultService(vaultRepository, new VaultProperties(MAX_SIZE));
    }

    private String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void getVault_returnsEncodedBlobAndVersion() {
        VaultEntity entity = VaultEntity.builder()
                .userId(userId)
                .blob("cipher".getBytes(StandardCharsets.UTF_8))
                .version(3L)
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(vaultRepository.findByUserId(userId)).thenReturn(Optional.of(entity));

        VaultResponse response = vaultService.getVault(userId);

        assertThat(response).satisfies(r -> {
            assertThat(r.version()).isEqualTo(3L);
            assertThat(new String(Base64.getDecoder().decode(r.vault()), StandardCharsets.UTF_8)).isEqualTo("cipher");
            assertThat(r.updatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        });
    }

    @Test
    void getVault_missing_throwsNotFound() {
        when(vaultRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vaultService.getVault(userId))
                .isInstanceOf(VaultNotFoundException.class);
    }

    @Test
    void updateVault_matchingVersion_bumpsAndPersists() {
        VaultEntity entity = VaultEntity.builder()
                .userId(userId).blob("old".getBytes(StandardCharsets.UTF_8))
                .version(2L).updatedAt(Instant.now()).build();
        when(vaultRepository.findAndLockByUserId(userId)).thenReturn(Optional.of(entity));

        VaultUpdateResponse response = vaultService.updateVault(userId, new UpdateVaultRequest(base64("new"), 2L));

        assertThat(response.version()).isEqualTo(3L);
        assertThat(new String(entity.getBlob(), StandardCharsets.UTF_8)).isEqualTo("new");
    }

    @Test
    void updateVault_staleVersion_throwsConflict() {
        VaultEntity entity = VaultEntity.builder()
                .userId(userId).blob("old".getBytes(StandardCharsets.UTF_8))
                .version(5L).updatedAt(Instant.now()).build();
        when(vaultRepository.findAndLockByUserId(userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> vaultService.updateVault(userId, new UpdateVaultRequest(base64("new"), 2L)))
                .isInstanceOf(VaultVersionConflictException.class);
    }

    @Test
    void updateVault_invalidBase64_throwsBadRequest() {
        assertThatThrownBy(() -> vaultService.updateVault(userId, new UpdateVaultRequest("not base64 !!!", 0L)))
                .isInstanceOf(InvalidVaultPayloadException.class);
    }

    @Test
    void updateVault_oversizeBlob_throwsTooLarge() {
        String big = Base64.getEncoder().encodeToString(new byte[(int) MAX_SIZE + 1]);

        assertThatThrownBy(() -> vaultService.updateVault(userId, new UpdateVaultRequest(big, 0L)))
                .isInstanceOf(VaultTooLargeException.class);
    }
}
