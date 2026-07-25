package com.wharf.backend.services.auth;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.AccountSetupRequest;
import com.wharf.backend.model.action.ChangePasswordRequest;
import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.RecoveryResetRequest;
import com.wharf.backend.model.action.RecoveryVerifyRequest;
import com.wharf.backend.model.action.RefreshRequest;
import com.wharf.backend.model.action.RegisterRequest;
import com.wharf.backend.model.action.ResendVerificationRequest;
import com.wharf.backend.model.action.VerifyEmailRequest;
import com.wharf.backend.model.core.RecoveryVerifyResponse;
import com.wharf.backend.model.core.RegistrationResponse;
import com.wharf.backend.model.core.TokenMode;
import com.wharf.backend.model.core.UserDto;
import com.wharf.backend.model.core.VaultUpdateResponse;
import com.wharf.backend.model.exception.AccountSetupConflictException;
import com.wharf.backend.model.exception.EmailAlreadyRegisteredException;
import com.wharf.backend.model.exception.EmailNotVerifiedException;
import com.wharf.backend.model.exception.InvalidCredentialsException;
import com.wharf.backend.model.exception.InvalidRecoveryCodeException;
import com.wharf.backend.model.exception.InvalidTokenException;
import com.wharf.backend.model.exception.InvalidVerificationCodeException;
import com.wharf.backend.model.exception.PasswordNotSetException;
import com.wharf.backend.model.exception.UserNotFoundException;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.security.JwtService;
import com.wharf.backend.security.TokenType;
import com.wharf.backend.services.UserMapper;
import com.wharf.backend.services.vault.VaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Account lifecycle: registration, login, token refresh and recovery-code reset.
 * The server only ever handles client-derived keys, which it bcrypt-hashes before
 * storing — it never sees passwords or plaintext vaults.
 *
 * <p>Every path here that mints tokens first insists the account's email address has been
 * verified. Registration alone proves nothing about the address, and an unproven address
 * is enough to claim a project invite addressed to it.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Arbitrary value hashed once at startup purely to equalise timing (see {@link #dummyKeyHash}). */
    private static final String DUMMY_KEY = "wharf-timing-equalizer";

    private final UserRepository userRepository;
    private final VaultService vaultService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;

    /**
     * A valid bcrypt hash, generated once, that unknown-email login/recovery attempts are
     * matched against so an attacker cannot distinguish a missing account (no hash to
     * compare) from a wrong key by response timing. The error is identical either way.
     */
    private final String dummyKeyHash;

    public AuthService(UserRepository userRepository,
                       VaultService vaultService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.vaultService = vaultService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emailVerificationService = emailVerificationService;
        this.dummyKeyHash = passwordEncoder.encode(DUMMY_KEY);
    }

    /**
     * Create the account and start email verification. Deliberately issues <em>no</em>
     * tokens: the account is inert until the emailed code is submitted to
     * {@link #verifyEmail}, which is what actually completes onboarding.
     */
    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        UserEntity user;
        try {
            // Flush eagerly so the unique-email constraint fires here, inside the try:
            // two concurrent registrations of the same email would otherwise both pass the
            // existsByEmail check and one would blow up as a 500 at commit time.
            user = userRepository.saveAndFlush(UserEntity.builder()
                    .id(UUID.randomUUID())
                    .email(email)
                    .authKeyHash(passwordEncoder.encode(request.authKey()))
                    .recoveryKeyHash(passwordEncoder.encode(request.recoveryAuthKey()))
                    // Unverified until the emailed code comes back.
                    .emailVerified(false)
                    .tokenVersion(0)
                    .createdAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyRegisteredException();
        }

        vaultService.createInitialVault(user.getId(), request.vault());
        emailVerificationService.startVerification(user);

        log.debug("Registered new account {} — awaiting email verification", user.getId());
        return new RegistrationResponse(email, true);
    }

    /**
     * Complete registration: consume the emailed code and hand back a full session, exactly
     * as a login would. This is the point at which an account becomes usable.
     */
    // noRollbackFor mirrors EmailVerificationService.verify: this is the outermost boundary,
    // so it is what actually decides whether the recorded failed attempt survives.
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public TokenIssue verifyEmail(VerifyEmailRequest request) {
        UserEntity user = emailVerificationService.verify(request.email(), request.code());
        log.debug("Email verification completed for account {}", user.getId());
        return issue(user, request.tokenModeOrDefault());
    }

    /** Re-send the verification code. Silently no-ops for unknown or already-verified addresses. */
    @Transactional
    public void resendVerification(ResendVerificationRequest request) {
        emailVerificationService.resend(request.email());
    }

    @Transactional(readOnly = true)
    public TokenIssue login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(EmailNormalizer.normalize(request.email())).orElse(null);
        // Always run bcrypt (against a dummy hash for unknown emails AND for OAuth-only
        // accounts that never set a password) so an absent account, an unset password and a
        // wrong key all take comparable time — no user enumeration via response timing.
        String hash = user != null && user.getAuthKeyHash() != null ? user.getAuthKeyHash() : dummyKeyHash;
        if (!passwordEncoder.matches(request.authKey(), hash) || user == null || user.getAuthKeyHash() == null) {
            throw new InvalidCredentialsException();
        }
        // Checked *after* the bcrypt comparison above on purpose: short-circuiting here would
        // skip the equalising hash for unverified accounts and reintroduce a timing signal.
        requireVerified(user);
        log.debug("Login for account {}", user.getId());
        return issue(user, request.tokenModeOrDefault());
    }

    @Transactional(readOnly = true)
    public TokenIssue refresh(String refreshToken, TokenMode mode) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidTokenException("No refresh token supplied");
        }
        JwtService.ParsedToken parsed = jwtService.parse(refreshToken, TokenType.REFRESH);
        UserEntity user = userRepository.findById(parsed.userId())
                .orElseThrow(() -> new UserNotFoundException(parsed.userId()));
        if (user.getTokenVersion() != parsed.tokenVersion()) {
            throw new InvalidTokenException("Refresh token has been revoked");
        }
        requireVerified(user);
        return issue(user, mode);
    }

    @Transactional(readOnly = true)
    public RecoveryVerifyResponse recoverVerify(RecoveryVerifyRequest request) {
        UserEntity user = requireRecoveryMatch(request.email(), request.recoveryAuthKey());
        return new RecoveryVerifyResponse(vaultService.getBlobBase64(user.getId()));
    }

    @Transactional
    public TokenIssue recoverReset(RecoveryResetRequest request) {
        UserEntity user = requireRecoveryMatch(request.email(), request.recoveryAuthKey());
        // Recovery reset mints a session, so it is a token-issuance path like any other and
        // has to respect the verification gate. Without this an unverified account could skip
        // verification entirely: whoever registered it also chose its recovery code, so they
        // could simply "recover" their way to a working session on an unproven address.
        requireVerified(user);

        user.setAuthKeyHash(passwordEncoder.encode(request.newAuthKey()));
        user.setRecoveryKeyHash(passwordEncoder.encode(request.newRecoveryAuthKey()));
        // Bumping the token version invalidates every previously issued token, forcing
        // all signed-in devices to re-authenticate.
        user.setTokenVersion(user.getTokenVersion() + 1);

        vaultService.replaceOnReset(user.getId(), request.vault());

        log.debug("Recovery reset completed for account {} (token version now v{})",
                user.getId(), user.getTokenVersion());
        return issue(user, request.tokenModeOrDefault());
    }

    private UserEntity requireRecoveryMatch(String email, String recoveryAuthKey) {
        UserEntity user = userRepository.findByEmail(EmailNormalizer.normalize(email)).orElse(null);
        // Same timing-equalisation as login: bcrypt always runs (against the dummy hash for
        // an unknown email or an account with no recovery key set yet), so all failures are
        // indistinguishable to a caller.
        String hash = user != null && user.getRecoveryKeyHash() != null ? user.getRecoveryKeyHash() : dummyKeyHash;
        if (!passwordEncoder.matches(recoveryAuthKey, hash) || user == null || user.getRecoveryKeyHash() == null) {
            throw new InvalidRecoveryCodeException();
        }
        return user;
    }

    /**
     * One-time onboarding for an account created via OAuth: atomically sets the recovery
     * key, creates the initial vault and — optionally — a password auth key derived from
     * the same master password the vault was just encrypted with. Strictly first-time:
     * an existing recovery key or vault (or an existing password when {@code authKey} is
     * supplied) is a conflict; rotation stays exclusive to the recover/reset flow.
     */
    @Transactional
    public void setupAccount(UUID userId, AccountSetupRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRecoveryKeyHash() != null || vaultService.existsForUser(userId)) {
            throw new AccountSetupConflictException("Account is already set up (recovery key or vault exists)");
        }
        if (request.hasAuthKey() && user.getAuthKeyHash() != null) {
            throw new AccountSetupConflictException("A password is already set for this account");
        }

        // The vault goes first: createInitialVault validates the blob (base64 + size cap)
        // before any write, so a bad payload aborts here with nothing persisted. Everything
        // below shares this transaction — a failure at any point rolls back recovery, vault
        // and password together (no account can end up with a recovery key but no vault).
        vaultService.createInitialVault(userId, request.vault());

        user.setRecoveryKeyHash(passwordEncoder.encode(request.recoveryAuthKey()));
        if (request.hasAuthKey()) {
            user.setAuthKeyHash(passwordEncoder.encode(request.authKey()));
        }
        log.debug("Completed account setup for {} (password login: {})", userId, request.hasAuthKey());
    }

    /**
     * Authenticated master-password change: verify the current auth key, swap in a hash of
     * the new one and store the vault re-encrypted under the new password. Sessions are left
     * intact — a device pairing is independent of the password, and the recovery code is
     * untouched (its slot in the re-encrypted blob still opens). Requires a password to
     * already exist; OAuth-first accounts set one via {@link #setupAccount}.
     */
    @Transactional
    public VaultUpdateResponse changePassword(UUID userId, ChangePasswordRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getAuthKeyHash() == null) {
            throw new PasswordNotSetException();
        }
        if (!passwordEncoder.matches(request.currentAuthKey(), user.getAuthKeyHash())) {
            throw new InvalidCredentialsException();
        }

        user.setAuthKeyHash(passwordEncoder.encode(request.newAuthKey()));
        VaultUpdateResponse updated = vaultService.replaceOnPasswordChange(userId, request.vault());

        log.debug("Master password changed for account {} (vault now v{})", userId, updated.version());
        return updated;
    }

    /**
     * Refuse to mint tokens for an account that has not proven control of its address.
     * Shared by every issuing path so there is exactly one place this rule lives.
     */
    private void requireVerified(UserEntity user) {
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
    }

    private TokenIssue issue(UserEntity user, TokenMode mode) {
        return new TokenIssue(UserMapper.toDto(user),
                jwtService.issueIdentityToken(user),
                jwtService.issueRefreshToken(user),
                mode);
    }

    /** Freshly issued tokens plus the caller's chosen delivery mode. */
    public record TokenIssue(UserDto user, String accessToken, String refreshToken, TokenMode mode) {
    }
}
