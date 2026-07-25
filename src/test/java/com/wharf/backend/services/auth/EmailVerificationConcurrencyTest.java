package com.wharf.backend.services.auth;

import com.wharf.backend.configuration.Profiles;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.exception.InvalidVerificationCodeException;
import com.wharf.backend.repository.EmailVerificationRepository;
import com.wharf.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the guess-budget race. A 6-digit code is only ~20 bits, so the
 * {@code MAX_ATTEMPTS} cap — not the code length — is what makes verification safe. If the
 * read-check-increment of {@code attempts} is not serialised, parallel guesses all observe
 * the same pre-increment value and the budget never binds, handing an attacker effectively
 * unlimited tries. The pessimistic lock in
 * {@link EmailVerificationRepository#findAndLockByUserId} is what makes the cap real.
 */
@SpringBootTest
@ActiveProfiles(Profiles.TEST)
class EmailVerificationConcurrencyTest {

    private static final int PARALLEL_GUESSES = 8;
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private EmailVerificationService verificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationRepository verificationRepository;

    @Test
    void concurrentWrongGuesses_cannotOutrunTheAttemptBudget() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@acme.io";
        UserEntity user = userRepository.saveAndFlush(UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .authKeyHash("auth")
                .recoveryKeyHash("recovery")
                .emailVerified(false)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());
        verificationService.startVerification(user);

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_GUESSES);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        // Every guess is wrong, and they are fired simultaneously. Whatever interleaving the
        // database picks, none may be accepted and the budget must not be exceeded.
        Callable<Void> guess = () -> {
            start.await();
            try {
                verificationService.verify(email, "000000");
                accepted.incrementAndGet();
            } catch (InvalidVerificationCodeException expected) {
                // The only legal outcome.
            } catch (RuntimeException ignored) {
                // A lock-contention failure is acceptable: the guess simply did not land.
            }
            return null;
        };

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < PARALLEL_GUESSES; i++) {
            futures.add(executor.submit(guess));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertThat(accepted.get())
                .as("a wrong code must never be accepted, however the guesses interleave")
                .isZero();
        assertThat(user.getId()).isNotNull();
        assertThat(verificationRepository.findByUserId(user.getId()))
                .as("%d parallel wrong guesses must burn the %d-attempt budget and destroy the code",
                        PARALLEL_GUESSES, MAX_ATTEMPTS)
                .isEmpty();
        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified())
                .as("the account must still be unverified")
                .isFalse();
    }
}
