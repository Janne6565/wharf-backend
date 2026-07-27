package com.wharf.backend.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Every collaboration notification and whether it is currently on.
 *
 * <p>Always complete — all seven keys are present on every response — so a client can render
 * the full list from one call and never has to guess what a missing key meant. The security
 * notices are not included: they have no key, cannot be switched off, and listing them here
 * would suggest otherwise.
 */
@Schema(description = """
        Collaboration-mail preferences, keyed by the stable notification key. Always contains \
        all seven keys. Security notices are not represented: they cannot be turned off.""")
public record NotificationPreferencesResponse(

        @Schema(example = """
                {"projectInvite": true, "inviteAccepted": true, "inviteDeclined": true, \
                "projectAccessGranted": true, "projectAccessRemoved": true, "roleChanged": true, \
                "projectDeleted": false}""")
        Map<String, Boolean> preferences
) {
}
