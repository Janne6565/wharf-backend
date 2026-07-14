package com.wharf.backend.services.auth;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.LoginRequest;
import com.wharf.backend.model.action.RecoveryResetRequest;
import com.wharf.backend.model.action.RecoveryVerifyRequest;
import com.wharf.backend.model.action.RefreshRequest;
import com.wharf.backend.model.action.RegisterRequest;
import com.wharf.backend.model.core.AuthResponse;
import com.wharf.backend.model.core.RecoveryVerifyResponse;
import com.wharf.backend.model.core.TokenMode;
import com.wharf.backend.model.core.TokenPair;
import com.wharf.backend.model.core.UserDto;
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

    private final UserRepository userRepository;
    private final VaultService vaultService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       VaultService vaultService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.vaultService = vaultService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        UserEntity user = userRepository.save(UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .authKeyHash(passwordEncoder.encode(request.authKey()))
                .recoveryKeyHash(passwordEncoder.encode(request.recoveryAuthKey()))
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());

        vaultService.createInitialVault(user.getId(), request.vault());

        log.debug("Registered new account {}", user.getId());
        return new AuthResponse(UserMapper.toDto(user), issuePair(user));
    }

    @Transactional(readOnly = true)
    public TokenIssue login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.authKey(), user.getAuthKeyHash())) {
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
    public AuthResponse recoverReset(RecoveryResetRequest request) {
        UserEntity user = requireRecoveryMatch(request.email(), request.recoveryAuthKey());

        user.setAuthKeyHash(passwordEncoder.encode(request.newAuthKey()));
        user.setRecoveryKeyHash(passwordEncoder.encode(request.newRecoveryAuthKey()));
        // Bumping the token version invalidates every previously issued token, forcing
        // all signed-in devices to re-authenticate.
        user.setTokenVersion(user.getTokenVersion() + 1);

        vaultService.replaceOnReset(user.getId(), request.vault());

        log.debug("Recovery reset completed for account {} (token version now v{})",
                user.getId(), user.getTokenVersion());
        return new AuthResponse(UserMapper.toDto(user), issuePair(user));
    }

    private UserEntity requireRecoveryMatch(String email, String recoveryAuthKey) {
        UserEntity user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(InvalidRecoveryCodeException::new);
        if (!passwordEncoder.matches(recoveryAuthKey, user.getRecoveryKeyHash())) {
            throw new InvalidRecoveryCodeException();
        }
        return user;
    }

    private TokenIssue issue(UserEntity user, TokenMode mode) {
        return new TokenIssue(UserMapper.toDto(user),
                jwtService.issueIdentityToken(user),
                jwtService.issueRefreshToken(user),
                mode);
    }

    private TokenPair issuePair(UserEntity user) {
        return new TokenPair(jwtService.issueIdentityToken(user), jwtService.issueRefreshToken(user));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    /** Freshly issued tokens plus the caller's chosen delivery mode. */
    public record TokenIssue(UserDto user, String accessToken, String refreshToken, TokenMode mode) {
    }
}
