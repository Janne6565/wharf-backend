package com.wharf.backend.services.auth;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.AccountSetupRequest;
import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.RecoveryResetRequest;
import com.wharf.backend.model.action.RecoveryVerifyRequest;
import com.wharf.backend.model.action.RegisterRequest;
import com.wharf.backend.model.core.TokenMode;
import com.wharf.backend.model.exception.EmailAlreadyRegisteredException;
import com.wharf.backend.model.exception.AccountSetupConflictException;
import com.wharf.backend.model.exception.InvalidCredentialsException;
import com.wharf.backend.model.exception.InvalidRecoveryCodeException;
import com.wharf.backend.model.exception.InvalidTokenException;
import com.wharf.backend.model.exception.InvalidVaultPayloadException;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.security.JwtService;
import com.wharf.backend.security.TokenType;
import com.wharf.backend.services.vault.VaultService;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issueIdentityToken(any())).thenReturn("id-token");
        when(jwtService.issueRefreshToken(any())).thenReturn("refresh-token");

        AuthService.TokenIssue issue = authService.register(
                new RegisterRequest("Deniz@Acme.io ", "authKey", "recoveryKey", "dmF1bHQ=", TokenMode.DIRECT));

        assertThat(issue.user().email()).isEqualTo("deniz@acme.io");
        assertThat(issue.accessToken()).isEqualTo("id-token");
        assertThat(issue.refreshToken()).isEqualTo("refresh-token");
        assertThat(issue.mode()).isEqualTo(TokenMode.DIRECT);
        verify(vaultService).createInitialVault(any(UUID.class), eq("dmF1bHQ="));
    }

    @Test
    void register_duplicateEmail_throwsConflict() {
        when(userRepository.existsByEmail("dupe@acme.io")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("dupe@acme.io", "a", "r", "dmF1bHQ=", null)))
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

        AuthService.TokenIssue issue = authService.recoverReset(new RecoveryResetRequest(
                "deniz@acme.io", "recoveryKey", "newAuth", "newRecovery", "bmV3", TokenMode.DIRECT));

        assertThat(user.getTokenVersion()).isEqualTo(1);
        assertThat(user.getAuthKeyHash()).isEqualTo("new-hash");
        assertThat(issue.accessToken()).isEqualTo("id-token");
        assertThat(issue.mode()).isEqualTo(TokenMode.DIRECT);
        verify(vaultService).replaceOnReset(user.getId(), "bmV3");
    }

    @Test
    void recoverReset_wrongCode_throwsInvalidRecovery() {
        UserEntity user = user("deniz@acme.io");
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("bad", "recovery-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.recoverReset(new RecoveryResetRequest(
                "deniz@acme.io", "bad", "newAuth", "newRecovery", "bmV3", null)))
                .isInstanceOf(InvalidRecoveryCodeException.class);
    }

    @Test
    void register_uniqueConstraintRace_translatesToConflict() {
        when(userRepository.existsByEmail("race@acme.io")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("race@acme.io", "a", "r", "dmF1bHQ=", null)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void login_unknownEmail_stillRunsBcryptToEqualiseTiming() {
        when(userRepository.findByEmail("ghost@acme.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@acme.io", "x", null)))
                .isInstanceOf(InvalidCredentialsException.class);
        // The dummy-hash comparison must run for a missing account, so an attacker cannot
        // enumerate accounts by timing the response.
        verify(passwordEncoder).matches(eq("x"), any());
    }

    @Test
    void recoverVerify_unknownEmail_stillRunsBcryptToEqualiseTiming() {
        when(userRepository.findByEmail("ghost@acme.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.recoverVerify(new RecoveryVerifyRequest("ghost@acme.io", "x")))
                .isInstanceOf(InvalidRecoveryCodeException.class);
        verify(passwordEncoder).matches(eq("x"), any());
    }

    @Test
    void login_oauthOnlyAccountWithNoPassword_behavesLikeWrongPasswordAndStillRunsBcrypt() {
        // An OAuth-only account has a null authKeyHash. A password login must be rejected
        // identically to a wrong password, INCLUDING running bcrypt against the dummy hash
        // so timing cannot reveal that the account exists but has no password.
        UserEntity oauthUser = user("oauth@acme.io");
        oauthUser.setAuthKeyHash(null);
        when(userRepository.findByEmail("oauth@acme.io")).thenReturn(Optional.of(oauthUser));

        assertThatThrownBy(() -> authService.login(new LoginRequest("oauth@acme.io", "any", null)))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwordEncoder).matches(eq("any"), any());
    }

    private UserEntity oauthOnlyUser() {
        UserEntity user = user("oauth@acme.io");
        user.setAuthKeyHash(null);
        user.setRecoveryKeyHash(null);
        return user;
    }

    @Test
    void setupAccount_firstTime_createsVaultAndSetsRecovery() {
        UserEntity oauthUser = oauthOnlyUser();
        when(userRepository.findById(oauthUser.getId())).thenReturn(Optional.of(oauthUser));
        when(vaultService.existsForUser(oauthUser.getId())).thenReturn(false);
        when(passwordEncoder.encode("new-recovery")).thenReturn("recovery-hash");

        authService.setupAccount(oauthUser.getId(),
                new AccountSetupRequest("new-recovery", "dmF1bHQ=", null));

        verify(vaultService).createInitialVault(oauthUser.getId(), "dmF1bHQ=");
        assertThat(oauthUser.getRecoveryKeyHash()).isEqualTo("recovery-hash");
        // No authKey supplied -> the account stays OAuth-only for login.
        assertThat(oauthUser.getAuthKeyHash()).isNull();
    }

    @Test
    void setupAccount_withAuthKey_alsoEnablesPasswordLogin() {
        UserEntity oauthUser = oauthOnlyUser();
        when(userRepository.findById(oauthUser.getId())).thenReturn(Optional.of(oauthUser));
        when(vaultService.existsForUser(oauthUser.getId())).thenReturn(false);
        when(passwordEncoder.encode("new-recovery")).thenReturn("recovery-hash");
        when(passwordEncoder.encode("new-auth")).thenReturn("auth-hash");

        authService.setupAccount(oauthUser.getId(),
                new AccountSetupRequest("new-recovery", "dmF1bHQ=", "new-auth"));

        assertThat(oauthUser.getRecoveryKeyHash()).isEqualTo("recovery-hash");
        assertThat(oauthUser.getAuthKeyHash()).isEqualTo("auth-hash");
        verify(vaultService).createInitialVault(oauthUser.getId(), "dmF1bHQ=");
    }

    @Test
    void setupAccount_recoveryAlreadySet_throwsConflict() {
        UserEntity user = user("deniz@acme.io"); // builder sets recoveryKeyHash "recovery-hash"
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.setupAccount(user.getId(),
                new AccountSetupRequest("new-recovery", "dmF1bHQ=", null)))
                .isInstanceOf(AccountSetupConflictException.class);
        verify(vaultService, never()).createInitialVault(any(), any());
    }

    @Test
    void setupAccount_vaultAlreadyExists_throwsConflict() {
        UserEntity oauthUser = oauthOnlyUser();
        when(userRepository.findById(oauthUser.getId())).thenReturn(Optional.of(oauthUser));
        when(vaultService.existsForUser(oauthUser.getId())).thenReturn(true);

        assertThatThrownBy(() -> authService.setupAccount(oauthUser.getId(),
                new AccountSetupRequest("new-recovery", "dmF1bHQ=", null)))
                .isInstanceOf(AccountSetupConflictException.class);
        verify(vaultService, never()).createInitialVault(any(), any());
    }

    @Test
    void setupAccount_authKeySuppliedButPasswordAlreadySet_throwsConflict() {
        UserEntity user = oauthOnlyUser();
        user.setAuthKeyHash("existing-auth-hash");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(vaultService.existsForUser(user.getId())).thenReturn(false);

        assertThatThrownBy(() -> authService.setupAccount(user.getId(),
                new AccountSetupRequest("new-recovery", "dmF1bHQ=", "new-auth")))
                .isInstanceOf(AccountSetupConflictException.class);
        // No silent credential overwrite, and nothing else is written either.
        assertThat(user.getAuthKeyHash()).isEqualTo("existing-auth-hash");
        verify(vaultService, never()).createInitialVault(any(), any());
    }

    @Test
    void setupAccount_invalidVault_failsBeforeRecoveryIsSet() {
        UserEntity oauthUser = oauthOnlyUser();
        when(userRepository.findById(oauthUser.getId())).thenReturn(Optional.of(oauthUser));
        when(vaultService.existsForUser(oauthUser.getId())).thenReturn(false);
        doThrow(new InvalidVaultPayloadException("Vault blob is not valid base64"))
                .when(vaultService).createInitialVault(oauthUser.getId(), "not-base64!");

        assertThatThrownBy(() -> authService.setupAccount(oauthUser.getId(),
                new AccountSetupRequest("new-recovery", "not-base64!", null)))
                .isInstanceOf(InvalidVaultPayloadException.class);
        // The vault is validated first, so the recovery key was never touched.
        assertThat(oauthUser.getRecoveryKeyHash()).isNull();
    }
}
