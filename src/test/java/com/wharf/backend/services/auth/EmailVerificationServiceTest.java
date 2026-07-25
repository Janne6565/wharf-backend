package com.wharf.backend.services.auth;

import com.wharf.backend.entity.EmailVerificationEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.exception.InvalidVerificationCodeException;
import com.wharf.backend.repository.EmailVerificationRepository;
import com.wharf.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationRepository verificationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailVerificationNotificationService notificationService;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(verificationRepository, userRepository,
                passwordEncoder, notificationService);
    }

    private UserEntity unverifiedUser() {
        return UserEntity.builder()
                .id(UUID.randomUUID())
                .email("deniz@acme.io")
                .emailVerified(false)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build();
    }

    private EmailVerificationEntity pending(UUID userId, Instant expiresAt, int attempts) {
        return EmailVerificationEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .codeHash("code-hash")
                .expiresAt(expiresAt)
                .attempts(attempts)
                .lastSentAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void startVerification_storesHashedSixDigitCodeAndMailsIt() {
        UserEntity user = unverifiedUser();
        when(verificationRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("code-hash");

        service.startVerification(user);

        ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(encoded.capture());
        assertThat(encoded.getValue()).matches("\\d{6}");

        ArgumentCaptor<EmailVerificationEntity> saved =
                ArgumentCaptor.forClass(EmailVerificationEntity.class);
        verify(verificationRepository).save(saved.capture());
        assertThat(saved.getValue().getCodeHash()).isEqualTo("code-hash");
        assertThat(saved.getValue().getAttempts()).isZero();
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now());

        // The plaintext code only ever leaves via the mail, never into the row.
        verify(notificationService).sendVerificationCode(eq("deniz@acme.io"), eq(encoded.getValue()), any());
    }

    @Test
    void startVerification_existingPending_replacesCodeAndResetsAttempts() {
        UserEntity user = unverifiedUser();
        EmailVerificationEntity existing = pending(user.getId(), Instant.now().plusSeconds(60), 4);
        when(verificationRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode(anyString())).thenReturn("fresh-hash");

        service.startVerification(user);

        // Upsert, not insert: exactly one code stays live per account, with a clean budget.
        assertThat(existing.getCodeHash()).isEqualTo("fresh-hash");
        assertThat(existing.getAttempts()).isZero();
        verify(verificationRepository).save(existing);
    }

    @Test
    void verify_correctCode_marksVerifiedAndDeletesTheChallenge() {
        UserEntity user = unverifiedUser();
        EmailVerificationEntity verification = pending(user.getId(), Instant.now().plusSeconds(600), 2);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(verificationRepository.findAndLockByUserId(user.getId())).thenReturn(Optional.of(verification));
        when(passwordEncoder.matches("123456", "code-hash")).thenReturn(true);

        UserEntity result = service.verify(" Deniz@Acme.io ", "123456");

        assertThat(result).isSameAs(user);
        assertThat(user.isEmailVerified()).isTrue();
        verify(verificationRepository).delete(verification);
    }

    @Test
    void verify_wrongCode_incrementsAttemptsAndKeepsTheChallengeAlive() {
        UserEntity user = unverifiedUser();
        EmailVerificationEntity verification = pending(user.getId(), Instant.now().plusSeconds(600), 1);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(verificationRepository.findAndLockByUserId(user.getId())).thenReturn(Optional.of(verification));
        when(passwordEncoder.matches("000000", "code-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verify("deniz@acme.io", "000000"))
                .isInstanceOf(InvalidVerificationCodeException.class);

        assertThat(verification.getAttempts()).isEqualTo(2);
        assertThat(user.isEmailVerified()).isFalse();
        verify(verificationRepository, never()).delete(any());
    }

    @Test
    void verify_fifthWrongAttempt_destroysTheChallenge() {
        // Six digits is only ~20 bits, so the attempt cap — not the code length — is what
        // makes this safe: the fifth miss burns the code and forces a resend.
        UserEntity user = unverifiedUser();
        EmailVerificationEntity verification = pending(user.getId(), Instant.now().plusSeconds(600), 4);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(verificationRepository.findAndLockByUserId(user.getId())).thenReturn(Optional.of(verification));
        when(passwordEncoder.matches("000000", "code-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verify("deniz@acme.io", "000000"))
                .isInstanceOf(InvalidVerificationCodeException.class);

        assertThat(verification.getAttempts()).isEqualTo(5);
        verify(verificationRepository).delete(verification);
    }

    @Test
    void verify_attemptsExhausted_rejectsEvenTheCorrectCode() {
        UserEntity user = unverifiedUser();
        EmailVerificationEntity verification = pending(user.getId(), Instant.now().plusSeconds(600), 5);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(verificationRepository.findAndLockByUserId(user.getId())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verify("deniz@acme.io", "123456"))
                .isInstanceOf(InvalidVerificationCodeException.class);

        // The code is never even compared once the budget is gone.
        verify(passwordEncoder, never()).matches(any(), any());
        verify(verificationRepository).delete(verification);
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void verify_expiredCode_isRejectedAndCleanedUp() {
        UserEntity user = unverifiedUser();
        EmailVerificationEntity verification = pending(user.getId(), Instant.now().minusSeconds(1), 0);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(verificationRepository.findAndLockByUserId(user.getId())).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> service.verify("deniz@acme.io", "123456"))
                .isInstanceOf(InvalidVerificationCodeException.class);

        verify(passwordEncoder, never()).matches(any(), any());
        verify(verificationRepository).delete(verification);
    }

    @Test
    void verify_unknownEmail_failsIdenticallyToAWrongCode() {
        when(userRepository.findByEmail("ghost@acme.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("ghost@acme.io", "123456"))
                .isInstanceOf(InvalidVerificationCodeException.class);
    }

    @Test
    void verify_alreadyVerifiedAccount_failsIdenticallyToAWrongCode() {
        UserEntity user = unverifiedUser();
        user.setEmailVerified(true);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.verify("deniz@acme.io", "123456"))
                .isInstanceOf(InvalidVerificationCodeException.class);
        verify(verificationRepository, never()).findAndLockByUserId(any());
    }

    @Test
    void resend_unknownEmail_isASilentNoOp() {
        when(userRepository.findByEmail("ghost@acme.io")).thenReturn(Optional.empty());

        service.resend("ghost@acme.io");

        verify(verificationRepository, never()).save(any());
        verify(notificationService, never()).sendVerificationCode(any(), any(), any());
    }

    @Test
    void resend_alreadyVerifiedEmail_isASilentNoOp() {
        UserEntity user = unverifiedUser();
        user.setEmailVerified(true);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));

        service.resend("deniz@acme.io");

        verify(notificationService, never()).sendVerificationCode(any(), any(), any());
    }

    @Test
    void resend_withinCooldown_sendsNothing() {
        UserEntity user = unverifiedUser();
        EmailVerificationEntity verification = pending(user.getId(), Instant.now().plusSeconds(600), 0);
        verification.setLastSentAt(Instant.now().minusSeconds(5));
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(verificationRepository.findByUserId(user.getId())).thenReturn(Optional.of(verification));

        service.resend("deniz@acme.io");

        verify(verificationRepository, never()).save(any());
        verify(notificationService, never()).sendVerificationCode(any(), any(), any());
    }

    @Test
    void resend_afterCooldown_issuesAFreshCode() {
        UserEntity user = unverifiedUser();
        EmailVerificationEntity verification = pending(user.getId(), Instant.now().plusSeconds(600), 3);
        verification.setLastSentAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(verificationRepository.findByUserId(user.getId())).thenReturn(Optional.of(verification));
        when(passwordEncoder.encode(anyString())).thenReturn("fresh-hash");

        service.resend("deniz@acme.io");

        assertThat(verification.getCodeHash()).isEqualTo("fresh-hash");
        assertThat(verification.getAttempts()).isZero();
        verify(notificationService).sendVerificationCode(eq("deniz@acme.io"), anyString(), any());
    }
}
