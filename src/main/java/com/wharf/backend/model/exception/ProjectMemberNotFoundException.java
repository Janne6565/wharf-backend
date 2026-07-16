package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * The target user of a member-scoped admin operation (e.g. sealing a key to them) is not a
 * member of the project.
 */
public class ProjectMemberNotFoundException extends BaseException {

    public ProjectMemberNotFoundException(UUID userId) {
        super(HttpStatus.NOT_FOUND, "User is not a member of this project: " + userId);
    }
}
