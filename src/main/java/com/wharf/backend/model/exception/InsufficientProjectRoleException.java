package com.wharf.backend.model.exception;

import com.wharf.backend.model.core.ProjectRole;
import org.springframework.http.HttpStatus;

/**
 * The caller is a member of the project but lacks the role the operation requires
 * (e.g. a MEMBER attempting an admin action, or an ADMIN removing another ADMIN).
 */
public class InsufficientProjectRoleException extends BaseException {

    public InsufficientProjectRoleException(ProjectRole required) {
        super(HttpStatus.FORBIDDEN, "This action requires at least the " + required + " role");
    }

    public InsufficientProjectRoleException(String detail) {
        super(HttpStatus.FORBIDDEN, detail);
    }
}
