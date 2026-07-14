package com.wharf.backend.services.devicecode;

import com.wharf.backend.configuration.DeviceCodeProperties;
import com.wharf.backend.entity.DeviceCodeEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.DeviceCodeExchangeRequest;
import com.wharf.backend.model.core.DeviceCodeResponse;
import com.wharf.backend.model.core.SessionResponse;
import com.wharf.backend.model.exception.DeviceCodeNotFoundException;
import com.wharf.backend.model.exception.DeviceCodeUnusableException;
import com.wharf.backend.model.exception.UserNotFoundException;
import com.wharf.backend.repository.DeviceCodeRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.security.JwtService;
import com.wharf.backend.services.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Device-pairing codes for the TUI. A code is an 8-character, unambiguous
 * (no 0/O/1/I) one-time secret, stored only as a SHA-256 hash and exchangeable for a
 * DIRECT session before it expires.
 */
@Service
public class DeviceCodeService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCodeService.class);

    /** Uppercase alphanumerics minus the visually ambiguous 0, O, 1 and I. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;

    private final DeviceCodeRepository deviceCodeRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final DeviceCodeProperties properties;
    private final SecureRandom random = new SecureRandom();

    public DeviceCodeService(DeviceCodeRepository deviceCodeRepository,
                             UserRepository userRepository,
                             JwtService jwtService,
                             DeviceCodeProperties properties) {
        this.deviceCodeRepository = deviceCodeRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Transactional
    public DeviceCodeResponse issue(UUID userId) {
        // Issuing a fresh code invalidates any the caller had outstanding.
        deviceCodeRepository.deleteUnusedByUserId(userId);

        String code = generateUniqueCode();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.ttl());

        deviceCodeRepository.save(DeviceCodeEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .codeHash(hash(code))
                .expiresAt(expiresAt)
                .createdAt(now)
                .build());

        log.debug("Issued device code for user {} (expires {})", userId, expiresAt);
        return new DeviceCodeResponse(code, expiresAt);
    }

    @Transactional
    public SessionResponse exchange(DeviceCodeExchangeRequest request) {
        String normalized = normalize(request.code());
        // Lock the row for the duration of the transaction so the used/expired check and
        // the mark-as-used below are atomic: a concurrent exchange of the same code
        // blocks here and then sees usedAt set (410) rather than also succeeding.
        DeviceCodeEntity code = deviceCodeRepository.findAndLockByCodeHash(hash(normalized))
                .orElseThrow(DeviceCodeNotFoundException::new);

        // Expired and already-used both return 410 so callers can't tell them apart.
        if (code.getUsedAt() != null || code.getExpiresAt().isBefore(Instant.now())) {
            throw new DeviceCodeUnusableException();
        }

        code.setUsedAt(Instant.now());

        UserEntity user = userRepository.findById(code.getUserId())
                .orElseThrow(() -> new UserNotFoundException(code.getUserId()));

        log.debug("Device code exchanged for user {} (device: {})",
                user.getId(), request.deviceName() == null ? "unnamed" : request.deviceName());
        return new SessionResponse(UserMapper.toDto(user),
                jwtService.issueIdentityToken(user),
                jwtService.issueRefreshToken(user));
    }

    private String generateUniqueCode() {
        // Retry on the astronomically rare hash collision with a live code.
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = randomCode();
            if (deviceCodeRepository.findByCodeHash(hash(candidate)).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique device code");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private String normalize(String code) {
        return code.replace("-", "").replaceAll("\\s", "").toUpperCase();
    }

    private String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
