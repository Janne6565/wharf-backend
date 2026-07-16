package com.wharf.backend.services.project;

import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.exception.InsufficientProjectRoleException;
import com.wharf.backend.model.exception.ProjectNotFoundException;
import com.wharf.backend.repository.ProjectMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Central, hand-rolled project access control (the repo convention is no {@code @PreAuthorize}).
 * Every project-scoped operation begins here. The key security rule: a non-member gets a 404,
 * never a 403 — they must not be able to tell a project they lack access to from one that does
 * not exist. Only a genuine member who is under-privileged gets a 403.
 *
 * <p>The helpers are {@link Propagation#MANDATORY} — they only ever run as part of a caller's
 * transaction, so the membership read joins the same unit of work as the mutation it guards.</p>
 */
@Service
public class ProjectAccessService {

    private final ProjectMemberRepository memberRepository;

    public ProjectAccessService(ProjectMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectMemberEntity requireMember(UUID projectId, UUID userId) {
        return memberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectMemberEntity requireAdmin(UUID projectId, UUID userId) {
        return requireRole(projectId, userId, ProjectRole.ADMIN);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectMemberEntity requireOwner(UUID projectId, UUID userId) {
        return requireRole(projectId, userId, ProjectRole.OWNER);
    }

    private ProjectMemberEntity requireRole(UUID projectId, UUID userId, ProjectRole minimum) {
        ProjectMemberEntity member = requireMember(projectId, userId);
        if (!member.getRole().atLeast(minimum)) {
            throw new InsufficientProjectRoleException(minimum);
        }
        return member;
    }
}
