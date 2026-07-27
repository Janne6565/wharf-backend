package com.wharf.backend.services.devicecode;

import com.wharf.backend.configuration.DeviceCodeProperties;
import com.wharf.backend.entity.DeviceCodeEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.DeviceCodeExchangeRequest;
import com.wharf.backend.model.core.DeviceCodeResponse;
import com.wharf.backend.model.core.SessionResponse;
import com.wharf.backend.model.exception.DeviceCodeNotFoundException;
import com.wharf.backend.model.exception.DeviceCodeUnusableException;
import com.wharf.backend.model.exception.EmailNotVerifiedException;
import com.wharf.backend.repository.DeviceCodeRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.security.JwtService;
import com.wharf.backend.services.auth.SecurityNotificationService;
import com.wharf.backend.services.mail.ActorContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
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
class DeviceCodeServiceTest {

    @Mock
    private DeviceCodeRepository deviceCodeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;

    @Mock
    private SecurityNotificationService securityNotifications;
    @Mock
    private ActorContextResolver actorContextResolver;

    private DeviceCodeService deviceCodeService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceCodeService = new DeviceCodeService(deviceCodeRepository, userRepository, jwtService,
                new DeviceCodeProperties(Duration.ofMinutes(10)),
                securityNotifications, actorContextResolver);
    }

    private UserEntity user() {
        return UserEntity.builder().id(userId).email("deniz@acme.io")
                .authKeyHash("a").recoveryKeyHash("r").emailVerified(true)
                .tokenVersion(0).createdAt(Instant.now()).build();
    }

    @Test
    void issue_generatesUnambiguousCodeAndInvalidatesPrevious() {
        when(deviceCodeRepository.findByCodeHash(any())).thenReturn(Optional.empty());

        DeviceCodeResponse response = deviceCodeService.issue(userId);

        verify(deviceCodeRepository).deleteUnusedByUserId(userId);
        verify(deviceCodeRepository).save(any(DeviceCodeEntity.class));
        assertThat(response.code()).matches("[A-HJ-NP-Z2-9]{8}");
        assertThat(response.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void exchange_validCode_marksUsedAndIssuesSession() {
        DeviceCodeEntity code = DeviceCodeEntity.builder()
                .id(UUID.randomUUID()).userId(userId).codeHash("hash")
                .expiresAt(Instant.now().plusSeconds(300)).createdAt(Instant.now()).build();
        when(deviceCodeRepository.findAndLockByCodeHash(any())).thenReturn(Optional.of(code));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));
        when(jwtService.issueIdentityToken(any())).thenReturn("id-token");
        when(jwtService.issueRefreshToken(any())).thenReturn("refresh-token");

        SessionResponse response = deviceCodeService.exchange(new DeviceCodeExchangeRequest("k7pq-m2xr", "laptop"));

        assertThat(code.getUsedAt()).isNotNull();
        assertThat(response.accessToken()).isEqualTo("id-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    /**
     * Pairing hands a long-lived session to a client the account holder may never have
     * touched, and nothing about the exchange is kept beyond "this code was spent" — so the
     * mail sent from the live request is the only record anyone ever sees of it.
     */
    @Test
    void exchange_validCode_notifiesTheAccountHolder() {
        DeviceCodeEntity code = DeviceCodeEntity.builder()
                .id(UUID.randomUUID()).userId(userId).codeHash("hash")
                .expiresAt(Instant.now().plusSeconds(300)).createdAt(Instant.now()).build();
        when(deviceCodeRepository.findAndLockByCodeHash(any())).thenReturn(Optional.of(code));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

        deviceCodeService.exchange(new DeviceCodeExchangeRequest("k7pq-m2xr", "laptop"));

        verify(securityNotifications).notifyDevicePaired(eq("deniz@acme.io"), eq("laptop"), any());
    }

    @Test
    void exchange_unusableCode_notifiesNobody() {
        when(deviceCodeRepository.findAndLockByCodeHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceCodeService.exchange(new DeviceCodeExchangeRequest("k7pq-m2xr", "laptop")))
                .isInstanceOf(DeviceCodeNotFoundException.class);

        verifyNoInteractions(securityNotifications);
    }

    @Test
    void exchange_unverifiedAccount_throwsEmailNotVerifiedAndIssuesNoSession() {
        // The TUI and the mobile OAuth hand-off both land here, so it is a token-issuing
        // path and gets the same verification gate as login.
        DeviceCodeEntity code = DeviceCodeEntity.builder()
                .id(UUID.randomUUID()).userId(userId).codeHash("hash")
                .expiresAt(Instant.now().plusSeconds(300)).createdAt(Instant.now()).build();
        UserEntity unverified = user();
        unverified.setEmailVerified(false);
        when(deviceCodeRepository.findAndLockByCodeHash(any())).thenReturn(Optional.of(code));
        when(userRepository.findById(userId)).thenReturn(Optional.of(unverified));

        assertThatThrownBy(() -> deviceCodeService.exchange(new DeviceCodeExchangeRequest("ABCD2345", null)))
                .isInstanceOf(EmailNotVerifiedException.class);
        verify(jwtService, never()).issueIdentityToken(any());
    }

    @Test
    void exchange_unknownCode_throwsNotFound() {
        when(deviceCodeRepository.findAndLockByCodeHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceCodeService.exchange(new DeviceCodeExchangeRequest("ABCD2345", null)))
                .isInstanceOf(DeviceCodeNotFoundException.class);
    }

    @Test
    void exchange_expiredCode_throwsGone() {
        DeviceCodeEntity code = DeviceCodeEntity.builder()
                .id(UUID.randomUUID()).userId(userId).codeHash("hash")
                .expiresAt(Instant.now().minusSeconds(1)).createdAt(Instant.now()).build();
        when(deviceCodeRepository.findAndLockByCodeHash(any())).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> deviceCodeService.exchange(new DeviceCodeExchangeRequest("ABCD2345", null)))
                .isInstanceOf(DeviceCodeUnusableException.class);
    }

    @Test
    void exchange_usedCode_throwsGone() {
        DeviceCodeEntity code = DeviceCodeEntity.builder()
                .id(UUID.randomUUID()).userId(userId).codeHash("hash")
                .expiresAt(Instant.now().plusSeconds(300)).usedAt(Instant.now().minusSeconds(5))
                .createdAt(Instant.now()).build();
        when(deviceCodeRepository.findAndLockByCodeHash(any())).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> deviceCodeService.exchange(new DeviceCodeExchangeRequest("ABCD2345", null)))
                .isInstanceOf(DeviceCodeUnusableException.class);
    }
}
