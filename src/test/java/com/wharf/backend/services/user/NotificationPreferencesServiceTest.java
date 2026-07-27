package com.wharf.backend.services.user;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.UpdateNotificationPreferencesRequest;
import com.wharf.backend.model.core.NotificationPreference;
import com.wharf.backend.model.exception.UnknownNotificationPreferenceException;
import com.wharf.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferencesServiceTest {

    @Mock
    private UserRepository userRepository;

    private NotificationPreferencesService service() {
        return new NotificationPreferencesService(userRepository);
    }

    private static UserEntity user() {
        return UserEntity.builder().id(UUID.randomUUID()).email("deniz@acme.io").build();
    }

    @Test
    void get_returnsAllSevenKeysDefaultingToOn() {
        UserEntity user = user();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        Map<String, Boolean> preferences = service().get(user.getId()).preferences();

        assertThat(preferences).hasSize(7).containsValue(true).doesNotContainValue(false);
        assertThat(preferences).containsKeys("projectInvite", "inviteAccepted", "inviteDeclined",
                "projectAccessGranted", "projectAccessRemoved", "roleChanged", "projectDeleted");
    }

    @Test
    void update_changesOnlyTheKeysNamed() {
        UserEntity user = user();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        Map<String, Boolean> result = service().update(user.getId(),
                new UpdateNotificationPreferencesRequest(Map.of("inviteDeclined", false))).preferences();

        assertThat(result).containsEntry("inviteDeclined", false);
        assertThat(result).containsEntry("inviteAccepted", true).containsEntry("projectInvite", true);
        assertThat(user.isNotifyInviteDeclined()).isFalse();
        assertThat(user.isNotifyInviteAccepted()).isTrue();
    }

    /** The master toggle writes all seven in one request. */
    @Test
    void update_acceptsTheWholeSetAtOnce() {
        UserEntity user = user();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        Map<String, Boolean> allOff = new HashMap<>();
        for (NotificationPreference preference : NotificationPreference.values()) {
            allOff.put(preference.key(), false);
        }

        Map<String, Boolean> result = service().update(user.getId(),
                new UpdateNotificationPreferencesRequest(allOff)).preferences();

        assertThat(result).hasSize(7).doesNotContainValue(true);
    }

    /**
     * Rejecting rather than ignoring is the point: a request to switch off a security notice
     * is indistinguishable from a typo, and answering 200 to either would leave the caller
     * believing it worked.
     */
    @Test
    void update_rejectsAnAttemptToNameASecurityNotice() {
        UserEntity user = user();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().update(user.getId(),
                new UpdateNotificationPreferencesRequest(Map.of("recoveryCodeUsed", false))))
                .isInstanceOf(UnknownNotificationPreferenceException.class);
    }

    @Test
    void update_validatesEveryKeyBeforeApplyingAny() {
        UserEntity user = user();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        Map<String, Boolean> mixed = new java.util.LinkedHashMap<>();
        mixed.put("projectInvite", false);
        mixed.put("devicePaired", false);

        assertThatThrownBy(() -> service().update(user.getId(),
                new UpdateNotificationPreferencesRequest(mixed)))
                .isInstanceOf(UnknownNotificationPreferenceException.class);

        // The valid key in the same request was not applied.
        assertThat(user.isNotifyProjectInvite()).isTrue();
    }

    @Test
    void update_rejectsANullValue() {
        UserEntity user = user();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().update(user.getId(),
                new UpdateNotificationPreferencesRequest(Collections.singletonMap("projectInvite", null))))
                .isInstanceOf(UnknownNotificationPreferenceException.class);
    }

    @Test
    void isEnabled_readsTheRecipientsStoredValue() {
        UserEntity user = user();
        user.setNotifyRoleChanged(false);
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));

        assertThat(service().isEnabled("deniz@acme.io", NotificationPreference.ROLE_CHANGED)).isFalse();
        assertThat(service().isEnabled("deniz@acme.io", NotificationPreference.PROJECT_INVITE)).isTrue();
    }

    @Test
    void isEnabled_normalisesTheAddress() {
        UserEntity user = user();
        when(userRepository.findByEmail("deniz@acme.io")).thenReturn(Optional.of(user));

        assertThat(service().isEnabled(" Deniz@Acme.IO ", NotificationPreference.PROJECT_INVITE)).isTrue();
    }

    /**
     * A project invite is the one mail that can reach a stranger. An address with no account
     * has expressed no preference, so refusing to send would make it impossible to invite
     * anyone new.
     */
    @Test
    void isEnabled_defaultsToOnForAnAddressWithNoAccount() {
        when(userRepository.findByEmail("stranger@acme.io")).thenReturn(Optional.empty());

        assertThat(service().isEnabled("stranger@acme.io", NotificationPreference.PROJECT_INVITE)).isTrue();
    }

    @Test
    void isEnabled_isFalseForNoRecipientAtAll() {
        assertThat(service().isEnabled(null, NotificationPreference.PROJECT_INVITE)).isFalse();
    }
}
