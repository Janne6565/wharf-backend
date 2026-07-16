package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * The invite does not exist, or is not addressed to the caller. As with projects, the two
 * cases are deliberately indistinguishable so a caller cannot probe for others' invites.
 */
public class InviteNotFoundException extends BaseException {

    public InviteNotFoundException(UUID inviteId) {
        super(HttpStatus.NOT_FOUND, "Invite not found: " + inviteId);
    }
}
