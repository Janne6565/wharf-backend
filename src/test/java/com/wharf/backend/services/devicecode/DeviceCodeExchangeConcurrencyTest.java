package com.wharf.backend.services.devicecode;

import com.wharf.backend.configuration.Profiles;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.DeviceCodeExchangeRequest;
import com.wharf.backend.model.core.DeviceCodeResponse;
import com.wharf.backend.model.core.SessionResponse;
import com.wharf.backend.model.exception.DeviceCodeUnusableException;
import com.wharf.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the one-time-use race: two devices exchanging the same pairing code at
 * once must not both get a session. The pessimistic lock in
 * {@link com.wharf.backend.repository.DeviceCodeRepository#findAndLockByCodeHash} serialises
 * the used/expired check-then-mark, so exactly one exchange wins and the other sees the
 * code already spent (410).
 */
@SpringBootTest
@ActiveProfiles(Profiles.TEST)
class DeviceCodeExchangeConcurrencyTest {

    @Autowired
    private DeviceCodeService deviceCodeService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void concurrentExchangesOfSameCode_onlyOneSucceeds() throws Exception {
        UUID userId = UUID.randomUUID();
        userRepository.saveAndFlush(UserEntity.builder()
                .id(userId)
                .email("race-" + userId + "@acme.io")
                .authKeyHash("auth")
                .recoveryKeyHash("recovery")
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());

        DeviceCodeResponse issued = deviceCodeService.issue(userId);
        DeviceCodeExchangeRequest request = new DeviceCodeExchangeRequest(issued.code(), "device");

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger unusable = new AtomicInteger();

        Callable<Void> attempt = () -> {
            start.await();
            try {
                SessionResponse session = deviceCodeService.exchange(request);
                assertThat(session.accessToken()).isNotBlank();
                successes.incrementAndGet();
            } catch (DeviceCodeUnusableException ex) {
                unusable.incrementAndGet();
            }
            return null;
        };

        Future<Void> first = executor.submit(attempt);
        Future<Void> second = executor.submit(attempt);
        start.countDown();
        first.get();
        second.get();
        executor.shutdown();

        assertThat(successes.get())
                .as("exactly one concurrent exchange of a one-time code must succeed")
                .isEqualTo(1);
        assertThat(unusable.get())
                .as("the losing exchange must see the code already spent (410)")
                .isEqualTo(1);
    }
}
