package com.wharf.backend.services.user;

import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.UpdatePublicKeyRequest;
import com.wharf.backend.model.core.UserProfileResponse;
import com.wharf.backend.model.exception.PublicKeyAlreadySetException;
import com.wharf.backend.model.exception.UserNotFoundException;
import com.wharf.backend.repository.ProjectMemberRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.project.ProjectCrypto;
import com.wharf.backend.services.vault.VaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Assembles the {@link UserProfileResponse} for the authenticated account and manages the
 * account's published X25519 public key (used to seal project DEKs).
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final ProjectMemberRepository memberRepository;
    private final VaultService vaultService;

    public UserService(UserRepository userRepository,
                       ProjectMemberRepository memberRepository,
                       VaultService vaultService) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.vaultService = vaultService;
    }

    public UserProfileResponse getProfile(UserEntity user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getCreatedAt(),
                user.getAuthKeyHash() != null,
                user.getRecoveryKeyHash() != null,
                vaultService.existsForUser(user.getId()),
                ProjectCrypto.encode(user.getPublicKey()));
    }

    /**
     * Publish, rotate or upgrade the account's public key. Rotating replaces the key
     * <em>and</em> invalidates every wrapped DEK the account holds: because the private key
     * changed, none of the old sealed boxes open any more, so each affected membership
     * re-enters the awaiting-key state until an admin re-seals the project DEK to the new
     * public key.
     *
     * <p>Upgrading replaces the key and <em>keeps</em> the wrapped DEKs. It exists for the
     * v1 -> v2 hybrid migration, where the new key embeds the same X25519 key and the
     * existing sealed boxes therefore still open — so forcing every project of every user
     * through awaiting-access would be pure churn. Zero-knowledge means the server cannot
     * check that claim; it does not have to, because a client that upgrades to an unrelated
     * key only makes its own DEKs unopenable, which a real rotate then fixes.
     */
    @Transactional
    public void updatePublicKey(UUID userId, UpdatePublicKeyRequest request) {
        byte[] publicKey = ProjectCrypto.decodePublicKey(request.publicKey());
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        boolean alreadySet = user.getPublicKey() != null;
        boolean upgradeRequested = Boolean.TRUE.equals(request.upgrade());
        if (alreadySet && !request.rotate() && !upgradeRequested) {
            throw new PublicKeyAlreadySetException();
        }

        user.setPublicKey(publicKey);
        user.setPublicKeyUpdatedAt(Instant.now());

        boolean upgrade = !request.rotate() && upgradeRequested && alreadySet;
        if (upgrade) {
            log.debug("Upgraded public key for {}; wrapped keys preserved", userId);
        } else if (request.rotate() && alreadySet) {
            List<ProjectMemberEntity> memberships = memberRepository.findByUserId(userId);
            for (ProjectMemberEntity membership : memberships) {
                membership.setWrappedDek(null);
                membership.setKeyedAt(null);
            }
            log.debug("Rotated public key for {}; reset {} wrapped keys", userId, memberships.size());
        } else {
            log.debug("Published public key for {}", userId);
        }
    }
}
