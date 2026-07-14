package com.wharf.backend.repository;

import com.wharf.backend.entity.VaultEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VaultRepository extends JpaRepository<VaultEntity, UUID> {

    Optional<VaultEntity> findByUserId(UUID userId);

    /**
     * Serialises concurrent vault writes so the read-check-write of the optimistic
     * version guard cannot lose an update.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VaultEntity v where v.userId = :userId")
    Optional<VaultEntity> findAndLockByUserId(@Param("userId") UUID userId);
}
