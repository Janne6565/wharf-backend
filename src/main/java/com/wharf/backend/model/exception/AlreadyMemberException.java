package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * An invite (or re-invite) was attempted for someone who is already a member of the project.
 */
public class AlreadyMemberException extends BaseException {

    public AlreadyMemberException() {
        super(HttpStatus.CONFLICT, "That user is already a member of this project");
    }
}
