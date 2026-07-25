package com.wharf.backend.repository;

import com.wharf.backend.entity.EmailVerificationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationRepository extends JpaRepository<EmailVerificationEntity, UUID> {

    Optional<EmailVerificationEntity> findByUserId(UUID userId);

    /**
     * Serialises concurrent verification attempts for one account, mirroring
     * {@code DeviceCodeRepository#findAndLockByCodeHash}. The attempt counter is the only
     * thing standing between a ~20-bit code and brute force, and an unlocked
     * read-check-increment lets parallel guesses all observe the same pre-increment value —
     * turning a 5-guess budget into an unbounded one. The lock makes the budget real.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerificationEntity v where v.userId = :userId")
    Optional<EmailVerificationEntity> findAndLockByUserId(@Param("userId") UUID userId);

    void deleteByUserId(UUID userId);
}
