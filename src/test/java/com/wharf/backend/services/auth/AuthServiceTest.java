package com.wharf.backend.services.auth;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.RecoveryResetRequest;
import com.wharf.backend.model.action.RegisterRequest;
import com.wharf.backend.model.core.AuthResponse;
import com.wharf.backend.model.core.TokenMode;
import com.wharf.backend.model.exception.EmailAlreadyRegisteredException;
import com.wharf.backend.model.exception.InvalidCredentialsException;
import com.wharf.backend.model.exception.InvalidRecoveryCodeException;
import com.wharf.backend.model.exception.InvalidTokenException;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.security.JwtService;
import com.wharf.backend.security.TokenType;
import com.wharf.backend.services.vault.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private VaultService vaultService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, vaultService, passwordEncoder, jwtService);
    }

    private UserEntity user(String email) {
        return UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .authKeyHash("auth-hash")
                .recoveryKeyHash("recovery-hash")
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void register_newEmail_persistsUserAndVaultAndIssuesTokens() {
        when(userRepository.existsByEmail("deniz@acme.io")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueIdentityToken(any())).thenReturn("id-token");
        when(jwtService.issueRefreshToken(any())).thenReturn("refresh-token");

        AuthResponse response = authService.register(
                new RegisterRequest("Deniz@Acme.io ", "authKey", "recoveryKey", "dmF1bHQ="));

        assertThat(response.user().email()).isEqualTo("deniz@acme.io");
        assertThat(response.tokens().accessToken()).isEqualTo("id-token");
        assertThat(response.tokens().refreshToken()).isEqualTo("refresh-token");
        verify(vaultService).createInitialVault(any(UUID.class), eq("dmF1bHQ="));
    }

    @Test
    void register_duplicateEmail_throwsConflict() {
        when(userRepository.existsByEmail("dupe@acme.io")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("dupe@acme.io", "a", "r", "dmF1bHQ=")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void login_validCredentials_returnsTokens() {
        UserEntity user = user("deniz@acme.io");
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("authKey", "auth-hash")).thenReturn(true);
        when(jwtService.issueIdentityToken(user)).thenReturn("id-token");
        when(jwtService.issueRefreshToken(user)).thenReturn("refresh-token");

        AuthService.TokenIssue issue = authService.login(new LoginRequest("deniz@acme.io", "authKey", TokenMode.DIRECT));

        assertThat(issue.accessToken()).isEqualTo("id-token");
        assertThat(issue.mode()).isEqualTo(TokenMode.DIRECT);
    }

    @Test
    void login_wrongKey_throwsInvalidCredentials() {
        UserEntity user = user("deniz@acme.io");
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("bad", "auth-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("deniz@acme.io", "bad", null)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail("ghost@acme.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@acme.io", "x", null)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_revokedTokenVersion_throwsInvalidToken() {
        UserEntity user = user("deniz@acme.io");
        user.setTokenVersion(2);
        when(jwtService.parse("refresh", TokenType.REFRESH))
                .thenReturn(new JwtService.ParsedToken(user.getId(), 1));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh("refresh", TokenMode.COOKIE))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void recoverReset_validCode_bumpsTokenVersionAndReplacesVault() {
        UserEntity user = user("deniz@acme.io");
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("recoveryKey", "recovery-hash")).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn("new-hash");
        when(jwtService.issueIdentityToken(user)).thenReturn("id-token");
        when(jwtService.issueRefreshToken(user)).thenReturn("refresh-token");

        AuthResponse response = authService.recoverReset(new RecoveryResetRequest(
                "deniz@acme.io", "recoveryKey", "newAuth", "newRecovery", "bmV3"));

        assertThat(user.getTokenVersion()).isEqualTo(1);
        assertThat(user.getAuthKeyHash()).isEqualTo("new-hash");
        assertThat(response.tokens().accessToken()).isEqualTo("id-token");
        verify(vaultService).replaceOnReset(user.getId(), "bmV3");
    }

    @Test
    void recoverReset_wrongCode_throwsInvalidRecovery() {
        UserEntity user = user("deniz@acme.io");
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("bad", "recovery-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.recoverReset(new RecoveryResetRequest(
                "deniz@acme.io", "bad", "newAuth", "newRecovery", "bmV3")))
                .isInstanceOf(InvalidRecoveryCodeException.class);
    }
}
