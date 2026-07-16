package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.entity.OAuthStateEntity;
import com.wharf.backend.model.core.OAuthClient;
import com.wharf.backend.repository.OAuthStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthStateStoreTest {

    @Mock
    private OAuthStateRepository stateRepository;

    private OAuthStateStore store;

    @BeforeEach
    void setUp() {
        store = new OAuthStateStore(stateRepository);
    }

    @Test
    void issue_savesAndReturnsRandomState() {
        when(stateRepository.save(any(OAuthStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String state = store.issue(OAuthClient.WEB);

        assertThat(state).isNotBlank().matches("[0-9a-f-]{36}");
        verify(stateRepository).save(any(OAuthStateEntity.class));
    }

    @Test
    void issue_persistsInitiatingClientOnTheStateRow() {
        when(stateRepository.save(any(OAuthStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        store.issue(OAuthClient.MOBILE);

        ArgumentCaptor<OAuthStateEntity> saved = ArgumentCaptor.forClass(OAuthStateEntity.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().getClient()).isEqualTo(OAuthClient.MOBILE);
    }

    @Test
    void consume_unknownState_returnsEmptyAndDeletesNothing() {
        when(stateRepository.findAndLockByState("nope")).thenReturn(Optional.empty());

        assertThat(store.consume("nope")).isEmpty();
        verify(stateRepository, never()).delete(any());
    }

    @Test
    void consume_validState_returnsItAndDeletesRow() {
        OAuthStateEntity entity = OAuthStateEntity.builder()
                .state("abc").createdAt(Instant.now()).build();
        when(stateRepository.findAndLockByState("abc")).thenReturn(Optional.of(entity));

        Optional<OAuthStateEntity> result = store.consume("abc");

        assertThat(result).contains(entity);
        // One-time: the row is destroyed on consumption so it can never be redeemed twice.
        verify(stateRepository).delete(entity);
    }

    @Test
    void consume_expiredState_returnsEmptyButStillDeletesRow() {
        OAuthStateEntity entity = OAuthStateEntity.builder()
                .state("old")
                .createdAt(Instant.now().minus(OAuthStateStore.STATE_TTL).minusSeconds(1))
                .build();
        when(stateRepository.findAndLockByState("old")).thenReturn(Optional.of(entity));

        assertThat(store.consume("old")).isEmpty();
        verify(stateRepository).delete(entity);
    }

    @Test
    void consume_blankState_returnsEmpty() {
        assertThat(store.consume("  ")).isEmpty();
        verify(stateRepository, never()).findAndLockByState(any());
    }

    @Test
    void evictExpired_deletesByThreshold() {
        store.evictExpired();
        verify(stateRepository).deleteExpired(any(Instant.class));
    }
}
