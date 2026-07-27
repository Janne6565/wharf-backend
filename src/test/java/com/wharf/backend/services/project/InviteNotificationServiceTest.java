package com.wharf.backend.services.project;

import com.wharf.backend.model.core.NotificationPreference;
import com.wharf.backend.services.mail.MailContent;
import com.wharf.backend.services.mail.TestMail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InviteNotificationServiceTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-07-30T12:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("0b1d2f6a-1111-4222-8333-444455556666");

    private final TestMail mail = new TestMail();
    private final InviteNotificationService service =
            new InviteNotificationService(mail.dispatcher(), mail.preferences());

    @Test
    void notifyInvited_namesTheInviterAndProjectInSubjectAndBody() {
        service.notifyInvited("invitee@acme.io", "admin@acme.io", "Acme", EXPIRES_AT);

        MailContent content = mail.sentTo("invitee@acme.io");
        assertThat(content.subject()).isEqualTo("admin@acme.io invited you to Acme on Wharf");
        assertThat(content.html()).contains("admin@acme.io invited you to Acme");
        assertThat(content.text()).contains("30 Jul 2026, 12:00 UTC");
    }

    /** The one mail that can reach a stranger, so it has to say what Wharf is. */
    @Test
    void notifyInvited_explainsWhatWharfIs() {
        service.notifyInvited("invitee@acme.io", "admin@acme.io", "Acme", EXPIRES_AT);

        assertThat(mail.sentTo("invitee@acme.io").text()).contains("terminal SSH client");
    }

    /**
     * Accepting must stay an authenticated action. A one-click accept link would hand project
     * access to whoever holds the inbox, so the only link points at the app.
     */
    @Test
    void notifyInvited_linksOnlyToTheApp() {
        service.notifyInvited("invitee@acme.io", "admin@acme.io", "Acme", EXPIRES_AT);

        assertThat(mail.sentTo("invitee@acme.io").text())
                .contains(TestMail.BASE_URL + "/projects")
                .doesNotContain("accept");
    }

    /**
     * Project names are chosen by other users and land in a third party's inbox, so the
     * renderer has to treat them as hostile input rather than as copy.
     */
    @Test
    void notifyInvited_escapesProjectNamesInHtml() {
        service.notifyInvited("invitee@acme.io", "admin@acme.io", "<script>alert(1)</script>", EXPIRES_AT);

        MailContent content = mail.sentTo("invitee@acme.io");
        assertThat(content.html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void notifyInviteAccepted_goesToTheInviterAndNamesTheNewMember() {
        service.notifyInviteAccepted("admin@acme.io", "invitee@acme.io", "Acme", PROJECT_ID, 7);

        MailContent content = mail.sentTo("admin@acme.io");
        assertThat(content.subject()).isEqualTo("invitee@acme.io accepted your invite to Acme");
        assertThat(content.text()).contains("invitee@acme.io").contains(PROJECT_ID.toString());
    }

    @Test
    void notifyInviteDeclined_saysNoAccessWasGranted() {
        service.notifyInviteDeclined("admin@acme.io", "invitee@acme.io", "Acme", PROJECT_ID, 6);

        assertThat(mail.sentTo("admin@acme.io").text()).contains("no access was granted");
    }

    /** Collaboration mail is opt-out-able in principle, so it carries the settings link. */
    @Test
    void collaborationMail_carriesNotificationSettingsLink() {
        service.notifyInviteAccepted("admin@acme.io", "invitee@acme.io", "Acme", PROJECT_ID, 7);

        assertThat(mail.sentTo("admin@acme.io").text()).contains("Notification settings:");
    }

    /**
     * An address with no Wharf account has expressed no preference. Refusing to send there
     * would make it impossible to invite anyone who is not already a user.
     */
    @Test
    void notifyInvited_stillReachesAnAddressWithNoAccount() {
        when(mail.preferences().isEnabled("stranger@acme.io", NotificationPreference.PROJECT_INVITE))
                .thenReturn(true);

        service.notifyInvited("stranger@acme.io", "admin@acme.io", "Acme", EXPIRES_AT);

        assertThat(mail.sentTo("stranger@acme.io")).isNotNull();
    }

    @Test
    void notifyInviteAccepted_optedOutInviter_getsNothing() {
        when(mail.preferences().isEnabled("admin@acme.io", NotificationPreference.INVITE_ACCEPTED))
                .thenReturn(false);

        service.notifyInviteAccepted("admin@acme.io", "invitee@acme.io", "Acme", PROJECT_ID, 7);

        verifyNoInteractions(mail.port());
    }

    @Test
    void notifyInvited_mailPortThrows_isSwallowed() {
        doThrow(new RuntimeException("boom")).when(mail.port()).send(anyString(), any());

        assertThatCode(() -> service.notifyInvited("invitee@acme.io", "admin@acme.io", "Acme", Instant.now()))
                .doesNotThrowAnyException();
    }
}
