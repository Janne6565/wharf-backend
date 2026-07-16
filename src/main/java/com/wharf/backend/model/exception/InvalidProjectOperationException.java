package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A structurally invalid project mutation that is neither a permission nor a concurrency
 * problem — e.g. attempting to remove the owner, removing a non-member, an owner demoting
 * themselves without transferring ownership, or a rotation supplying a wrapped key for a
 * user who is not (or no longer) a member.
 */
public class InvalidProjectOperationException extends BaseException {

    public InvalidProjectOperationException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
