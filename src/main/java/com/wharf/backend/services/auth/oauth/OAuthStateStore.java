package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.entity.OAuthStateEntity;
import com.wharf.backend.repository.OAuthStateRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side, one-time OAuth state. {@link #issue()} mints a random state and persists it;
 * {@link #consume(String)} atomically validates and destroys it under a pessimistic lock,
 * so a given state can be redeemed at most once and only within its TTL. This is what
 * protects the callback against CSRF and replay while keeping the API stateless.
 */
@Component
public class OAuthStateStore {

    static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final OAuthStateRepository stateRepository;

    public OAuthStateStore(OAuthStateRepository stateRepository) {
        this.stateRepository = stateRepository;
    }

    @Transactional
    public String issue() {
        String state = UUID.randomUUID().toString();
        stateRepository.save(OAuthStateEntity.builder()
                .state(state)
                .createdAt(Instant.now())
                .build());
        return state;
    }

    /**
     * Consume a state exactly once. Returns empty if it is unknown, already consumed or
     * expired; the row is deleted either way (an expired row is not left behind).
     */
    @Transactional
    public Optional<OAuthStateEntity> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        Optional<OAuthStateEntity> found = stateRepository.findAndLockByState(state);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        OAuthStateEntity entity = found.get();
        stateRepository.delete(entity);
        boolean withinTtl = entity.getCreatedAt().plus(STATE_TTL).isAfter(Instant.now());
        return withinTtl ? Optional.of(entity) : Optional.empty();
    }

    /** Periodically drop abandoned (never-consumed) states past their TTL. */
    @Scheduled(fixedRateString = "PT10M")
    @Transactional
    public void evictExpired() {
        stateRepository.deleteExpired(Instant.now().minus(STATE_TTL));
    }
}
