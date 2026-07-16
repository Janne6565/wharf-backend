package com.wharf.backend.repository;

import com.wharf.backend.entity.ProjectInviteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectInviteRepository extends JpaRepository<ProjectInviteEntity, UUID> {

    Optional<ProjectInviteEntity> findByProjectIdAndEmail(UUID projectId, String email);

    List<ProjectInviteEntity> findByProjectId(UUID projectId);

    List<ProjectInviteEntity> findByEmail(String email);

    long countByProjectId(UUID projectId);
}
