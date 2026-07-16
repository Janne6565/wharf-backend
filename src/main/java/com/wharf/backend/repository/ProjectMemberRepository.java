package com.wharf.backend.repository;

import com.wharf.backend.entity.ProjectMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMemberEntity, UUID> {

    Optional<ProjectMemberEntity> findByProjectIdAndUserId(UUID projectId, UUID userId);

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    List<ProjectMemberEntity> findByProjectId(UUID projectId);

    List<ProjectMemberEntity> findByUserId(UUID userId);

    long countByProjectId(UUID projectId);
}
