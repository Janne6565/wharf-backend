package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.entity.OAuthIdentityEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.repository.OAuthIdentityRepository;
import com.wharf.backend.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthUserServiceTest {

    @Mock
    private OAuthIdentityRepository identityRepository;
    @Mock
    private UserRepository userRepository;

    private OAuthUserService service;

    @BeforeEach
    void setUp() {
        service = new OAuthUserService(identityRepository, userRepository);
    }

    private UserEntity user(String email) {
        return UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
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
        verify(userRepository).save(any(UserEntity.class));
        verify(identityRepository).save(any(OAuthIdentityEntity.class));
    }
}
