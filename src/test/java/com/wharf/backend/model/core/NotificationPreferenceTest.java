package com.wharf.backend.model.core;

import com.wharf.backend.entity.UserEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPreferenceTest {

    /**
     * The security notices must stay unrepresentable. If a constant for one of them is ever
     * added, this fails — which is the point: it should take a deliberate deletion of this
     * test to make silencing a security notice expressible.
     */
    @Test
    void onlyCollaborationNotificationsHaveAKey() {
        assertThat(Arrays.stream(NotificationPreference.values()).map(NotificationPreference::key))
                .containsExactlyInAnyOrder("projectInvite", "inviteAccepted", "inviteDeclined",
                        "projectAccessGranted", "projectAccessRemoved", "roleChanged", "projectDeleted");
    }

    @Test
    void noSecurityNoticeCanBeNamed() {
        List<String> securityNotices = List.of("recoveryCodeUsed", "passwordChanged", "passwordSet",
                "devicePaired", "providerLinked", "accountDeleted");

        assertThat(securityNotices).allSatisfy(key ->
                assertThat(NotificationPreference.fromKey(key)).isEmpty());
    }

    @Test
    void everyPreferenceReadsAndWritesItsOwnFlag() {
        for (NotificationPreference preference : NotificationPreference.values()) {
            UserEntity user = UserEntity.builder().build();
            assertThat(preference.isEnabledFor(user))
                    .as("%s defaults to on", preference)
                    .isTrue();

            preference.setOn(user, false);
            assertThat(preference.isEnabledFor(user)).as("%s reads back what it wrote", preference).isFalse();

            // No other flag moved — a copy/paste slip in the accessor pair would show up here.
            assertThat(Arrays.stream(NotificationPreference.values())
                    .filter(other -> other != preference)
                    .filter(other -> !other.isEnabledFor(user)))
                    .as("%s did not disturb its neighbours", preference)
                    .isEmpty();
        }
    }

    @Test
    void keysAreLowerCamelCase() {
        assertThat(Arrays.stream(NotificationPreference.values()).map(NotificationPreference::key))
                .allSatisfy(key -> assertThat(key).matches("[a-z][a-zA-Z]*"));
    }

    /** The enum name is the column suffix; keeping them aligned is what makes the mapping readable. */
    @Test
    void enumNameMatchesItsKey() {
        for (NotificationPreference preference : NotificationPreference.values()) {
            assertThat(preference.key().replaceAll("([A-Z])", "_$1").toUpperCase(Locale.ENGLISH))
                    .isEqualTo(preference.name());
        }
    }
}
