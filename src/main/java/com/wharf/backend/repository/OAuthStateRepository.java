package com.wharf.backend.repository;

import com.wharf.backend.entity.OAuthStateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OAuthStateRepository extends JpaRepository<OAuthStateEntity, String> {

    /**
     * Serialises concurrent consumption of the same state so a one-time state can never be
     * redeemed twice: the second exchange blocks here, then finds the row already deleted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OAuthStateEntity s where s.state = :state")
    Optional<OAuthStateEntity> findAndLockByState(@Param("state") String state);

    /** Housekeeping: drop abandoned states whose TTL has elapsed. */
    @Modifying
    @Query("delete from OAuthStateEntity s where s.createdAt < :threshold")
    void deleteExpired(@Param("threshold") Instant threshold);
}
