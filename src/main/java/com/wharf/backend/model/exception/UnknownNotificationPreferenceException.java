package com.wharf.backend.model.exception;

import org.springframework.http.HttpStatus;

/**
 * A notification-preference update named a key that does not exist.
 *
 * <p>The security notices are the reason this rejects rather than ignores. Their keys are
 * unrepresentable — no enum constant, no column — so a request trying to switch one off looks
 * exactly like a typo, and both must fail loudly. Silently dropping the unknown key would
 * answer 200 to "stop emailing me when my recovery code is used" and leave the caller
 * believing it worked.
 */
public class UnknownNotificationPreferenceException extends BaseException {

    public UnknownNotificationPreferenceException(String key) {
        super(HttpStatus.BAD_REQUEST, "Unknown notification preference: " + key,
                "unknown_notification_preference");
    }
}
