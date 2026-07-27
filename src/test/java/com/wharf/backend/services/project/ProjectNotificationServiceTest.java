package com.wharf.backend.services.project;

import com.wharf.backend.model.core.NotificationPreference;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.services.mail.ActorContext;
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

class ProjectNotificationServiceTest {

    private static final String RECIPIENT = "member@acme.io";
    private static final String ADMIN = "admin@acme.io";
    private static final UUID PROJECT_ID = UUID.fromString("0b1d2f6a-1111-4222-8333-444455556666");
    private static final ActorContext ACTOR = new ActorContext(
            Instant.parse("2026-07-27T16:20:00Z"), "92.184.106.21", "Firefox 141 on macOS");

    private final TestMail mail = new TestMail();
    private final ProjectNotificationService service =
            new ProjectNotificationService(mail.dispatcher(), mail.preferences());

    @Test
    void accessGranted_explainsThatTheKeyArrivedAndHowToPullTheHosts() {
        service.notifyAccessGranted(RECIPIENT, "atlas", PROJECT_ID, ProjectRole.MEMBER, ADMIN);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.subject()).isEqualTo("You have access to atlas");
        assertThat(TestMail.unwrapped(content)).contains("Run wharf sync in your terminal");
        assertThat(content.text()).contains(TestMail.BASE_URL + "/projects/" + PROJECT_ID);
    }

    /**
     * These are calm by design: nothing is wrong, and borrowing the security palette would
     * blunt it for the mails that genuinely need it.
     */
    @Test
    void membershipMail_carriesNoAlertStylingOrCompromiseWarning() {
        service.notifyRemoved(RECIPIENT, "atlas", ADMIN, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.text()).startsWith("WHARF\n");
        assertThat(TestMail.unwrapped(content)).doesNotContain("may be compromised");
        assertThat(content.html()).doesNotContain("#e0685e").doesNotContain("#e3c078");
    }

    /** Removal is only expressible as a rotation, so this mail is the member's only signal. */
    @Test
    void removed_saysWhatHappensToTheirVaultAndWhoToAsk() {
        service.notifyRemoved(RECIPIENT, "atlas", ADMIN, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.subject()).isEqualTo("You were removed from atlas");
        assertThat(TestMail.unwrapped(content))
                .contains("dropped from your vault on the next sync")
                .contains("Your own hosts, keys and other projects are unchanged")
                .contains("go to the project admin, not to us");
    }

    /** Nothing left to open — a button pointing into the project would be a dead end. */
    @Test
    void removed_hasNoCallToActionIntoTheProject() {
        service.notifyRemoved(RECIPIENT, "atlas", ADMIN, ACTOR);

        assertThat(mail.sentTo(RECIPIENT).text()).doesNotContain("/projects/" + PROJECT_ID);
    }

    @Test
    void roleChanged_showsTheTransitionAndWhatTheNewRoleCanDo() {
        service.notifyRoleChanged(RECIPIENT, "atlas", PROJECT_ID,
                ProjectRole.MEMBER, ProjectRole.ADMIN, ADMIN, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.subject()).isEqualTo("Your role in atlas is now admin");
        assertThat(TestMail.unwrapped(content))
                .contains("member → admin")
                .contains("You are now an admin.")
                .contains("manage members, invites and project keys");
    }

    /** A project has exactly one owner, so that role takes the definite article. */
    @Test
    void roleChanged_readsCorrectlyForEveryRole() {
        service.notifyRoleChanged(RECIPIENT, "atlas", PROJECT_ID,
                ProjectRole.ADMIN, ProjectRole.OWNER, ADMIN, ACTOR);

        assertThat(TestMail.unwrapped(mail.sentTo(RECIPIENT))).contains("You are now the owner.");
    }

    @Test
    void projectDeleted_saysItIsNotRecoverableAndSparesPersonalHosts() {
        service.notifyProjectDeleted(RECIPIENT, "atlas", ADMIN, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.subject()).isEqualTo("atlas was deleted");
        assertThat(TestMail.unwrapped(content))
                .contains("Deleted projects are not recoverable")
                .contains("Hosts you added to your own vault separately are not affected");
    }

    @Test
    void everyMembershipMail_offersNotificationSettings() {
        service.notifyAccessGranted(RECIPIENT, "atlas", PROJECT_ID, ProjectRole.MEMBER, ADMIN);
        service.notifyRemoved(RECIPIENT, "atlas", ADMIN, ACTOR);
        service.notifyProjectDeleted(RECIPIENT, "atlas", ADMIN, ACTOR);

        assertThat(mail.allSent()).allSatisfy(content ->
                assertThat(content.text()).contains("Notification settings:"));
    }

    /**
     * The opt-out has to bite at send time, not merely be stored. The check lives in the
     * notification service so that a notification added later cannot bypass it by forgetting
     * to ask.
     */
    @Test
    void optedOutRecipient_getsNothing() {
        when(mail.preferences().isEnabled(RECIPIENT, NotificationPreference.PROJECT_ACCESS_REMOVED))
                .thenReturn(false);

        service.notifyRemoved(RECIPIENT, "atlas", ADMIN, ACTOR);

        verifyNoInteractions(mail.port());
    }

    /** Opting out of one notification must not silence the others. */
    @Test
    void optOutIsPerNotification() {
        when(mail.preferences().isEnabled(RECIPIENT, NotificationPreference.PROJECT_ACCESS_REMOVED))
                .thenReturn(false);

        service.notifyRemoved(RECIPIENT, "atlas", ADMIN, ACTOR);
        service.notifyProjectDeleted(RECIPIENT, "atlas", ADMIN, ACTOR);

        assertThat(mail.allSent()).singleElement()
                .satisfies(content -> assertThat(content.subject()).isEqualTo("atlas was deleted"));
    }

    @Test
    void deliveryFailure_isSwallowed() {
        doThrow(new RuntimeException("boom")).when(mail.port()).send(anyString(), any());

        assertThatCode(() -> service.notifyRemoved(RECIPIENT, "atlas", ADMIN, ACTOR))
                .doesNotThrowAnyException();
    }
}
