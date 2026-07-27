package com.wharf.backend.model.core;

import com.wharf.backend.entity.UserEntity;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The seven collaboration notifications a user may switch off, and the single place their
 * stable keys are enumerated.
 *
 * <p>Each constant binds its wire key to the accessors on {@link UserEntity}, so adding a
 * notification is one constant plus one column rather than an edit in five files — and so the
 * API, the persistence and the send-time check can never disagree about which flags exist.
 *
 * <p><strong>Security notices are absent by design.</strong> There is no constant for them,
 * which means there is no key a request could name and no column a value could be written to.
 * They are not "on by default"; they are unrepresentable. Wharf has no password reset, no
 * support-side recovery and no server-side activity log, so email is the only out-of-band
 * channel through which someone can find out their account was taken over — silencing it must
 * not be expressible.
 */
public enum NotificationPreference {

    /** You are invited to a project. */
    PROJECT_INVITE("projectInvite", UserEntity::isNotifyProjectInvite, UserEntity::setNotifyProjectInvite),

    /** An invite you sent is accepted. */
    INVITE_ACCEPTED("inviteAccepted", UserEntity::isNotifyInviteAccepted, UserEntity::setNotifyInviteAccepted),

    /** An invite you sent is declined. */
    INVITE_DECLINED("inviteDeclined", UserEntity::isNotifyInviteDeclined, UserEntity::setNotifyInviteDeclined),

    /** You are given access to a project (an admin sealed the project key to your account). */
    PROJECT_ACCESS_GRANTED("projectAccessGranted",
            UserEntity::isNotifyProjectAccessGranted, UserEntity::setNotifyProjectAccessGranted),

    /** Your access to a project is removed. */
    PROJECT_ACCESS_REMOVED("projectAccessRemoved",
            UserEntity::isNotifyProjectAccessRemoved, UserEntity::setNotifyProjectAccessRemoved),

    /** Your role on a project changes. */
    ROLE_CHANGED("roleChanged", UserEntity::isNotifyRoleChanged, UserEntity::setNotifyRoleChanged),

    /** A project you have access to is deleted. */
    PROJECT_DELETED("projectDeleted", UserEntity::isNotifyProjectDeleted, UserEntity::setNotifyProjectDeleted);

    private final String key;
    private final Predicate<UserEntity> reader;
    private final BiConsumer<UserEntity, Boolean> writer;

    NotificationPreference(String key, Predicate<UserEntity> reader, BiConsumer<UserEntity, Boolean> writer) {
        this.key = key;
        this.reader = reader;
        this.writer = writer;
    }

    /** The stable key used in the API payload. Never renamed — clients persist it. */
    public String key() {
        return key;
    }

    public boolean isEnabledFor(UserEntity user) {
        return reader.test(user);
    }

    public void setOn(UserEntity user, boolean enabled) {
        writer.accept(user, enabled);
    }

    public static Optional<NotificationPreference> fromKey(String key) {
        for (NotificationPreference preference : values()) {
            if (preference.key.equals(key)) {
                return Optional.of(preference);
            }
        }
        return Optional.empty();
    }
}
