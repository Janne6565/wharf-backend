package com.wharf.backend.services.auth;

import com.wharf.backend.entity.EmailVerificationEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.exception.InvalidVerificationCodeException;
import com.wharf.backend.repository.EmailVerificationRepository;
import com.wharf.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Proof-of-control over an email address via a one-time 6-digit code.
 *
 * <p>The code is generated with {@link SecureRandom} and stored only as a bcrypt hash —
 * never in plaintext, exactly like every other credential in this service. A leaked
 * database snapshot therefore does not hand out live codes.
 *
 * <p><strong>Why the attempt cap is the security control:</strong> six digits is only about
 * 20 bits of entropy (1 in a million), which brute force would chew through in seconds if
 * guesses were unbounded. The code length is a usability choice; what actually makes it
 * safe is that a code dies after {@value #MAX_ATTEMPTS} failed attempts (forcing a resend,
 * which mints an entirely new code) and after {@code TTL} regardless. The per-IP
 * {@code VERIFY} rate-limit bucket is the second layer.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** How long a freshly issued code stays usable. */
    private static final Duration TTL = Duration.ofMinutes(15);
    /** Failed attempts a single code tolerates before it is destroyed. */
    private static final int MAX_ATTEMPTS = 5;
    /** Minimum spacing between two resends for the same account. */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** Exclusive upper bound for the numeric code, i.e. the range is 000000–999999. */
    private static final int CODE_BOUND = 1_000_000;
    private static final String CODE_FORMAT = "%06d";

    private final EmailVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationNotificationService notificationService;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(EmailVerificationRepository verificationRepository,
                                    UserRepository userRepository,
                                    PasswordEncoder passwordEncoder,
                                    EmailVerificationNotificationService notificationService) {
        this.verificationRepository = verificationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
    }

    /**
     * Issue (or re-issue) a code for {@code user} and mail it out. Called on registration;
     * unconditional, so it carries no cooldown — the caller has just created the account.
     */
    @Transactional
    public void startVerification(UserEntity user) {
        issueAndSend(user, Instant.now());
    }

    /**
     * Consume a code. On success the account is marked verified, the pending row is
     * destroyed and the (now verified) user is returned so the caller can issue a session.
     *
     * <p>Every failure mode — unknown email, already verified, no pending code, expired,
     * exhausted attempts, plain wrong digits — raises the same
     * {@link InvalidVerificationCodeException}, so the endpoint cannot be used to probe
     * which addresses are registered or how much of a code was guessed correctly.
     *
     * <p>{@code noRollbackFor} is load-bearing, not a nicety: the whole point of the failure
     * path is to record the attempt (or destroy a spent challenge), and the default rollback
     * would undo exactly that, leaving the guess budget permanently at zero used.
     */
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public UserEntity verify(String email, String code) {
        UserEntity user = userRepository.findByEmail(EmailNormalizer.normalize(email))
                .orElseThrow(InvalidVerificationCodeException::new);
        if (user.isEmailVerified()) {
            throw new InvalidVerificationCodeException();
        }

        // Locked, not a plain read: see findAndLockByUserId — concurrent guesses would
        // otherwise each see the same attempt count and the budget would never bind.
        EmailVerificationEntity verification = verificationRepository.findAndLockByUserId(user.getId())
                .orElseThrow(InvalidVerificationCodeException::new);

        if (verification.getExpiresAt().isBefore(Instant.now())
                || verification.getAttempts() >= MAX_ATTEMPTS) {
            // A dead code is cleared out eagerly so the only way forward is a resend.
            verificationRepository.delete(verification);
            throw new InvalidVerificationCodeException();
        }

        // bcrypt's matches() is constant-time, so comparing the submitted code leaks nothing
        // about how many leading digits were right.
        if (!passwordEncoder.matches(code, verification.getCodeHash())) {
            verification.setAttempts(verification.getAttempts() + 1);
            if (verification.getAttempts() >= MAX_ATTEMPTS) {
                verificationRepository.delete(verification);
                log.debug("Verification code for account {} burnt through its attempt budget", user.getId());
            }
            throw new InvalidVerificationCodeException();
        }

        user.setEmailVerified(true);
        verificationRepository.delete(verification);
        log.debug("Email verified for account {}", user.getId());
        return user;
    }

    /**
     * Re-send the pending code for {@code email}, subject to the cooldown. Silently does
     * nothing when the address is unknown or already verified: the endpoint answers 202
     * either way, so it never reveals whether an account exists.
     */
    @Transactional
    public void resend(String email) {
        Optional<UserEntity> found = userRepository.findByEmail(EmailNormalizer.normalize(email));
        if (found.isEmpty() || found.get().isEmailVerified()) {
            return;
        }
        UserEntity user = found.get();

        Instant now = Instant.now();
        Optional<EmailVerificationEntity> pending = verificationRepository.findByUserId(user.getId());
        if (pending.isPresent() && pending.get().getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now)) {
            // Within the cooldown: drop the request rather than erroring, so a client that
            // double-taps "resend" cannot be told apart from one that waited.
            log.debug("Skipping verification resend for account {} — still within cooldown", user.getId());
            return;
        }

        issueAndSend(user, now);
    }

    /**
     * Upsert the account's single pending verification with a fresh code: the previous one
     * (and its accumulated attempts) is replaced outright, so exactly one code is ever live.
     */
    private void issueAndSend(UserEntity user, Instant now) {
        String code = randomCode();
        EmailVerificationEntity verification = verificationRepository.findByUserId(user.getId())
                .orElseGet(() -> EmailVerificationEntity.builder()
                        .id(UUID.randomUUID())
                        .userId(user.getId())
                        .createdAt(now)
                        .build());

        verification.setCodeHash(passwordEncoder.encode(code));
        verification.setExpiresAt(now.plus(TTL));
        verification.setAttempts(0);
        verification.setLastSentAt(now);
        verificationRepository.save(verification);

        notificationService.sendVerificationCode(user.getEmail(), code, TTL);
        log.debug("Issued email verification code for account {} (expires {})",
                user.getId(), verification.getExpiresAt());
    }

    private String randomCode() {
        return String.format(CODE_FORMAT, random.nextInt(CODE_BOUND));
    }
}
