package com.wharf.backend.repository;

import com.wharf.backend.entity.DeviceCodeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeviceCodeRepository extends JpaRepository<DeviceCodeEntity, UUID> {

    Optional<DeviceCodeEntity> findByCodeHash(String codeHash);

    /**
     * Serialises concurrent one-time-use exchanges of the same code so the
     * used/expired check-then-mark cannot race: two exchanges of the same code can
     * never both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeviceCodeEntity d where d.codeHash = :codeHash")
    Optional<DeviceCodeEntity> findAndLockByCodeHash(@Param("codeHash") String codeHash);

    /** Invalidate a user's previous unused codes when they issue a new one. */
    @Modifying
    @Query("delete from DeviceCodeEntity d where d.userId = :userId and d.usedAt is null")
    void deleteUnusedByUserId(@Param("userId") UUID userId);
}
