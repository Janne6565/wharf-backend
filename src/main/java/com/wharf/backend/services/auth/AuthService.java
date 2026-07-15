package com.wharf.backend.services.auth;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.AccountSetupRequest;
import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.RecoveryResetRequest;
import com.wharf.backend.model.action.RecoveryVerifyRequest;
import com.wharf.backend.model.action.RefreshRequest;
import com.wharf.backend.model.action.RegisterRequest;
import com.wharf.backend.model.core.RecoveryVerifyResponse;
import com.wharf.backend.model.core.TokenMode;
import com.wharf.backend.model.core.UserDto;
import com.wharf.backend.model.exception.AccountSetupConflictException;
import com.wharf.backend.model.exception.EmailAlreadyRegisteredException;
import com.wharf.backend.model.exception.InvalidCredentialsException;
import com.wharf.backend.model.exception.InvalidRecoveryCodeException;
import com.wharf.backend.model.exception.InvalidTokenException;
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

    /**
     * A valid bcrypt hash, generated once, that unknown-email login/recovery attempts are
     * matched against so an attacker cannot distinguish a missing account (no hash to
     * compare) from a wrong key by response timing. The error is identical either way.
     */
    private final String dummyKeyHash;

    public AuthService(UserRepository userRepository,
                       VaultService vaultService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.vaultService = vaultService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyKeyHash = passwordEncoder.encode(DUMMY_KEY);
    }

    @Transactional
    public TokenIssue register(RegisterRequest request) {
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
                    .tokenVersion(0)
                    .createdAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyRegisteredException();
        }

        vaultService.createInitialVault(user.getId(), request.vault());

        log.debug("Registered new account {}", user.getId());
        return issue(user, request.tokenModeOrDefault());
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
