package com.wharf.backend.repository;

import com.wharf.backend.entity.ProjectVaultEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProjectVaultRepository extends JpaRepository<ProjectVaultEntity, UUID> {

    Optional<ProjectVaultEntity> findByProjectId(UUID projectId);

    /**
     * Serialises concurrent project-vault writes (PUT and rotate) so the read-check-write of
     * the optimistic version guard cannot lose an update.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProjectVaultEntity v where v.projectId = :projectId")
    Optional<ProjectVaultEntity> findAndLockByProjectId(@Param("projectId") UUID projectId);
}
