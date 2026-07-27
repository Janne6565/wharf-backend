package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/**
 * A partial update: only the named keys change, everything else is left alone.
 *
 * <p>Partial rather than whole-object on purpose. Each row in the settings UI owns its own
 * request, so one failing write reverts one toggle instead of clobbering six unrelated ones
 * with whatever the client last rendered — and two people (or two tabs) changing different
 * notifications cannot overwrite each other.
 */
@Schema(description = """
        Notification preferences to change, keyed by the stable notification key. Only the keys \
        present are modified. An unknown key is rejected — including the security notices, which \
        have no key and cannot be turned off.""")
public record UpdateNotificationPreferencesRequest(

        @NotEmpty
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = """
                {"inviteDeclined": false}""")
        Map<String, Boolean> preferences
) {
}
