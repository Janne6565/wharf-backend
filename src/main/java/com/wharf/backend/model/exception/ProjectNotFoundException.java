package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * A project does not exist <em>or</em> the caller is not a member of it. The two cases are
 * deliberately indistinguishable: a non-member must never be able to tell a real project
 * they lack access to from one that does not exist (no existence leak).
 */
public class ProjectNotFoundException extends BaseException {

    public ProjectNotFoundException(UUID projectId) {
        super(HttpStatus.NOT_FOUND, "Project not found: " + projectId);
    }
}
