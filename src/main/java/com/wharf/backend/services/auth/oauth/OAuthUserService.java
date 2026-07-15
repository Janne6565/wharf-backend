package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.entity.OAuthIdentityEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.repository.OAuthIdentityRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.auth.EmailNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the local account behind a verified OAuth identity. Kept separate from
 * {@link OAuthService} so the database work runs in its own short transaction — the
 * external HTTP calls happen first, outside any open transaction.
 *
 * <p>Zero-knowledge note: an account created here has <em>no</em> password auth key,
 * recovery key or vault. OAuth authenticates; the master password (the vault-encryption
 * factor) stays client-side and is set later, after first login.
 */
@Service
public class OAuthUserService {

    private static final Logger log = LoggerFactory.getLogger(OAuthUserService.class);

    private final OAuthIdentityRepository identityRepository;
    private final UserRepository userRepository;

    public OAuthUserService(OAuthIdentityRepository identityRepository, UserRepository userRepository) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
    }

    /**
     * Find or create the account for a verified provider identity:
     * <ol>
     *   <li>a known {@code (provider, subject)} link → its user;</li>
     *   <li>otherwise an existing account with the same (normalized) verified email →
     *       auto-link the new identity to it;</li>
     *   <li>otherwise create a fresh OAuth-only account and link it.</li>
     * </ol>
     */
    @Transactional
    public UserEntity findOrCreateAndLink(OAuthProvider provider, OAuthUserIdentity identity) {
        Optional<OAuthIdentityEntity> existingLink =
                identityRepository.findByProviderAndSubject(provider, identity.subject());
        if (existingLink.isPresent()) {
            return userRepository.findById(existingLink.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "OAuth identity references missing user " + existingLink.get().getUserId()));
        }

        String email = EmailNormalizer.normalize(identity.email());
        UserEntity user = userRepository.findByEmail(email)
                .orElseGet(() -> createOAuthUser(email));

        linkIdentity(provider, identity, user, email);
        return user;
    }

    private UserEntity createOAuthUser(String email) {
        UserEntity user = userRepository.save(UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                // No password / recovery / vault yet — set client-side after first login.
                .authKeyHash(null)
                .recoveryKeyHash(null)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());
        log.debug("Created OAuth-only account {}", user.getId());
        return user;
    }

    private void linkIdentity(OAuthProvider provider, OAuthUserIdentity identity,
                              UserEntity user, String email) {
        identityRepository.save(OAuthIdentityEntity.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .provider(provider)
                .subject(identity.subject())
                .emailAtLink(email)
                .createdAt(Instant.now())
                .build());
        log.debug("Linked {} identity to account {}", provider, user.getId());
    }
}
