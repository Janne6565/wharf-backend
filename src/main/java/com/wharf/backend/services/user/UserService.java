package com.wharf.backend.services.user;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.UserProfileResponse;
import com.wharf.backend.services.vault.VaultService;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@link UserProfileResponse} for the authenticated account, combining the
 * user entity's credential state with a vault-existence lookup into the routing flags the
 * frontend needs.
 */
@Service
public class UserService {

    private final VaultService vaultService;

    public UserService(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    public UserProfileResponse getProfile(UserEntity user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getCreatedAt(),
                user.getAuthKeyHash() != null,
                user.getRecoveryKeyHash() != null,
                vaultService.existsForUser(user.getId()));
    }
}
