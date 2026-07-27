package com.wharf.backend.services.user;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.UpdateNotificationPreferencesRequest;
import com.wharf.backend.model.core.NotificationPreference;
import com.wharf.backend.model.core.NotificationPreferencesResponse;
import com.wharf.backend.model.exception.UnknownNotificationPreferenceException;
import com.wharf.backend.model.exception.UserNotFoundException;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.services.auth.EmailNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes the collaboration-mail opt-outs, and answers the one question the
 * notification services ask before every send: should this go out at all?
 *
 * <p>The security notices are not addressable here. {@link NotificationPreference} has no
 * constant for them, so there is no key to read, no key to write, and
 * {@link #isEnabled(String, NotificationPreference)} can only ever be asked about
 * collaboration mail.
 */
@Service
public class NotificationPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferencesService.class);

    private final UserRepository userRepository;

    public NotificationPreferencesService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse get(UUID userId) {
        return toResponse(userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId)));
    }

    /**
     * Apply a partial update. Every key is validated before any is written, so a request
     * naming one bad key changes nothing at all rather than half-applying.
     */
    @Transactional
    public NotificationPreferencesResponse update(UUID userId, UpdateNotificationPreferencesRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        Map<NotificationPreference, Boolean> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : request.preferences().entrySet()) {
            NotificationPreference preference = NotificationPreference.fromKey(entry.getKey())
                    .orElseThrow(() -> new UnknownNotificationPreferenceException(entry.getKey()));
            if (entry.getValue() == null) {
                throw new UnknownNotificationPreferenceException(entry.getKey());
            }
            resolved.put(preference, entry.getValue());
        }
        resolved.forEach((preference, enabled) -> preference.setOn(user, enabled));

        log.debug("Updated {} notification preference(s) for account {}", resolved.size(), userId);
        return toResponse(user);
    }

    /**
     * Whether {@code recipient} wants this notification.
     *
     * <p>Looked up by address rather than by id because that is what the notification services
     * hold — and because the recipient may not be a Wharf user at all. A project invite is the
     * one mail that can reach a stranger, and an address with no account has expressed no
     * preference, so the answer there is yes: refusing to send would make it impossible to
     * invite anyone new.
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(String recipient, NotificationPreference preference) {
        if (recipient == null) {
            return false;
        }
        return userRepository.findByEmail(EmailNormalizer.normalize(recipient))
                .map(preference::isEnabledFor)
                .orElse(true);
    }

    private static NotificationPreferencesResponse toResponse(UserEntity user) {
        Map<String, Boolean> preferences = new LinkedHashMap<>();
        for (NotificationPreference preference : NotificationPreference.values()) {
            preferences.put(preference.key(), preference.isEnabledFor(user));
        }
        return new NotificationPreferencesResponse(preferences);
    }
}
