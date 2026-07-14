package com.wharf.backend.repository;

import com.wharf.backend.entity.DeviceCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeviceCodeRepository extends JpaRepository<DeviceCodeEntity, UUID> {

    Optional<DeviceCodeEntity> findByCodeHash(String codeHash);

    /** Invalidate a user's previous unused codes when they issue a new one. */
    @Modifying
    @Query("delete from DeviceCodeEntity d where d.userId = :userId and d.usedAt is null")
    void deleteUnusedByUserId(@Param("userId") UUID userId);
}
