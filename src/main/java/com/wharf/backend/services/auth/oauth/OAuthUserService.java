package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.entity.OAuthIdentityEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.model.exception.UnverifiedAccountConflictException;
import com.wharf.backend.repository.OAuthIdentityRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.auth.EmailNormalizer;
import com.wharf.backend.services.auth.SecurityNotificationService;
import com.wharf.backend.services.mail.ActorContextResolver;
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
    private final SecurityNotificationService securityNotifications;
    private final ActorContextResolver actorContextResolver;

    public OAuthUserService(OAuthIdentityRepository identityRepository,
                            UserRepository userRepository,
                            SecurityNotificationService securityNotifications,
                            ActorContextResolver actorContextResolver) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.securityNotifications = securityNotifications;
        this.actorContextResolver = actorContextResolver;
    }

    /**
     * Find or create the account for a verified provider identity:
     * <ol>
     *   <li>a known {@code (provider, subject)} link → its user;</li>
     *   <li>otherwise an existing account with the same (normalized) verified email →
     *       auto-link the new identity to it, <em>provided</em> that account's own email is
     *       verified;</li>
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
        Optional<UserEntity> existingAccount = userRepository.findByEmail(email);
        boolean joiningExistingAccount = existingAccount.isPresent();
        UserEntity user = existingAccount
                .map(OAuthUserService::requireVerifiedForLinking)
                .orElseGet(() -> createOAuthUser(email));

        linkIdentity(provider, identity, user, email);

        // Only the auto-link case is worth a security notice: a new sign-in method was added
        // to an account that already existed, which is an access change its owner must be able
        // to notice. Case 3 above (a fresh OAuth-only account) links an identity too, but there
        // the link *is* the registration and there is no prior owner to warn.
        if (joiningExistingAccount) {
            securityNotifications.notifyProviderLinked(email, provider, actorContextResolver.resolve());
        }
        return user;
    }

    /**
     * Auto-linking by email is only safe when the local account has itself proven control
     * of that address. An unverified row may be a squatter who registered someone else's
     * email and set a password on it; linking the real owner's provider identity into it
     * would let the squatter co-inhabit the account (their password would keep working, and
     * their vault/recovery material would be the account's). Refuse instead.
     */
    private static UserEntity requireVerifiedForLinking(UserEntity existing) {
        if (!existing.isEmailVerified()) {
            throw new UnverifiedAccountConflictException();
        }
        return existing;
    }

    private UserEntity createOAuthUser(String email) {
        UserEntity user = userRepository.save(UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                // No password / recovery / vault yet — set client-side after first login.
                .authKeyHash(null)
                .recoveryKeyHash(null)
                // The provider already proved ownership of this address (OAuthService
                // rejects an unverified provider identity outright), so there is nothing
                // left for our own code-by-email challenge to establish.
                .emailVerified(true)
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
