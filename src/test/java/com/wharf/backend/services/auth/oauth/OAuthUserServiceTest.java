package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.entity.OAuthIdentityEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.model.exception.UnverifiedAccountConflictException;
import com.wharf.backend.repository.OAuthIdentityRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.auth.SecurityNotificationService;
import com.wharf.backend.services.mail.ActorContextResolver;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthUserServiceTest {

    @Mock
    private OAuthIdentityRepository identityRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SecurityNotificationService securityNotifications;
    @Mock
    private ActorContextResolver actorContextResolver;

    private OAuthUserService service;

    @BeforeEach
    void setUp() {
        service = new OAuthUserService(identityRepository, userRepository,
                securityNotifications, actorContextResolver);
    }

    private UserEntity user(String email) {
        return UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .emailVerified(true)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void existingIdentity_returnsLinkedUserWithoutCreatingAnything() {
        UserEntity existing = user("deniz@acme.io");
        OAuthIdentityEntity identity = OAuthIdentityEntity.builder()
                .id(UUID.randomUUID()).userId(existing.getId())
                .provider(OAuthProvider.GITHUB).subject("gh-1").build();
        when(identityRepository.findByProviderAndSubject(OAuthProvider.GITHUB, "gh-1"))
                .thenReturn(Optional.of(identity));
        when(userRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UserEntity result = service.findOrCreateAndLink(OAuthProvider.GITHUB,
                new OAuthUserIdentity("gh-1", "deniz@acme.io", true));

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).save(any());
        verify(identityRepository, never()).save(any());
    }

    @Test
    void noIdentityButEmailMatches_autoLinksToExistingAccount() {
        UserEntity existing = user("deniz@acme.io");
        when(identityRepository.findByProviderAndSubject(OAuthProvider.GOOGLE, "goog-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(existing));

        // Email arrives with different casing/whitespace — must normalise to the same account.
        UserEntity result = service.findOrCreateAndLink(OAuthProvider.GOOGLE,
                new OAuthUserIdentity("goog-1", " Deniz@Acme.io ", true));

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).save(any());
        ArgumentCaptor<OAuthIdentityEntity> saved = ArgumentCaptor.forClass(OAuthIdentityEntity.class);
        verify(identityRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(existing.getId());
        assertThat(saved.getValue().getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(saved.getValue().getEmailAtLink()).isEqualTo("deniz@acme.io");
    }

    @Test
    void noIdentityNoAccount_createsOAuthOnlyUserAndLinks() {
        when(identityRepository.findByProviderAndSubject(OAuthProvider.GOOGLE, "goog-2"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@acme.io")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity result = service.findOrCreateAndLink(OAuthProvider.GOOGLE,
                new OAuthUserIdentity("goog-2", "new@acme.io", true));

        assertThat(result.getEmail()).isEqualTo("new@acme.io");
        // OAuth-only account: no password / recovery hashes until set client-side.
        assertThat(result.getAuthKeyHash()).isNull();
        assertThat(result.getRecoveryKeyHash()).isNull();
        // The provider already proved ownership of the address, so no code challenge is owed.
        assertThat(result.isEmailVerified()).isTrue();
        verify(userRepository).save(any(UserEntity.class));
        verify(identityRepository).save(any(OAuthIdentityEntity.class));
    }

    /**
     * Adding a way into an account that already exists is an access change its owner has to
     * be able to notice — but only then. In the create case the link *is* the registration,
     * and there is no prior owner to warn.
     */
    @Test
    void autoLinkingToAnExistingAccount_notifiesItsOwner() {
        UserEntity existing = user("deniz@acme.io");
        when(identityRepository.findByProviderAndSubject(OAuthProvider.GOOGLE, "goog-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(existing));

        service.findOrCreateAndLink(OAuthProvider.GOOGLE,
                new OAuthUserIdentity("goog-1", "deniz@acme.io", true));

        verify(securityNotifications).notifyProviderLinked(
                eq("deniz@acme.io"), eq(OAuthProvider.GOOGLE), any());
    }

    @Test
    void creatingAFreshOAuthAccount_notifiesNobody() {
        when(identityRepository.findByProviderAndSubject(OAuthProvider.GOOGLE, "goog-2"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@acme.io")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.findOrCreateAndLink(OAuthProvider.GOOGLE,
                new OAuthUserIdentity("goog-2", "new@acme.io", true));

        verifyNoInteractions(securityNotifications);
    }

    @Test
    void noIdentityButUnverifiedEmailMatches_refusesToLink() {
        // The matching row may be a squatter who registered someone else's address; linking
        // the real owner's provider identity into it would let them share the account.
        UserEntity squatter = user("deniz@acme.io");
        squatter.setEmailVerified(false);
        when(identityRepository.findByProviderAndSubject(OAuthProvider.GOOGLE, "goog-3"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(squatter));

        assertThatThrownBy(() -> service.findOrCreateAndLink(OAuthProvider.GOOGLE,
                new OAuthUserIdentity("goog-3", "deniz@acme.io", true)))
                .isInstanceOf(UnverifiedAccountConflictException.class);
        verify(identityRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }
}
