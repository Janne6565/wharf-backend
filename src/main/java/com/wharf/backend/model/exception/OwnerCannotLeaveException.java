package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The owner attempted to leave a project. A project must always have exactly one owner, so
 * the owner must transfer ownership to another member before leaving.
 */
public class OwnerCannotLeaveException extends BaseException {

    public OwnerCannotLeaveException() {
        super(HttpStatus.CONFLICT, "Transfer ownership before leaving the project");
    }
}
