package com.wharf.backend.services.auth;

import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.services.mail.ActorContext;
import com.wharf.backend.services.mail.MailContent;
import com.wharf.backend.services.mail.TestMail;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

class SecurityNotificationServiceTest {

    private static final String RECIPIENT = "deniz@acme.io";
    private static final ActorContext ACTOR = new ActorContext(
            Instant.parse("2026-07-27T14:32:00Z"), "92.184.106.21", "Firefox 141 on macOS");

    private final TestMail mail = new TestMail();
    private final SecurityNotificationService service = new SecurityNotificationService(mail.dispatcher());

    @Test
    void recoveryCodeUsed_isTheHighestSeverityAndSaysSessionsWereRevoked() {
        service.notifyRecoveryCodeUsed(RECIPIENT, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.subject()).isEqualTo("Your Wharf recovery code was used");
        assertThat(content.text()).startsWith("WHARF -- SECURITY ALERT: RECOVERY CODE USED");
        assertThat(TestMail.unwrapped(content)).contains("All sessions were signed out");
        // Danger escalates the card border; a warning would not.
        assertThat(content.html()).contains("border:1px solid #5a2a28");
    }

    @Test
    void securityNotices_reportWhenWhereAndWithWhat() {
        service.notifyPasswordChanged(RECIPIENT, ACTOR);

        assertThat(mail.sentTo(RECIPIENT).text())
                .contains("27 Jul 2026, 14:32 UTC")
                .contains("92.184.106.21")
                .contains("Firefox 141 on macOS");
    }

    /**
     * There is no GeoIP lookup and no stored device history, so an unknown actor simply loses
     * those rows rather than showing blanks or a guess.
     */
    @Test
    void securityNotices_degradeToATimestampWhenTheRequestRevealedNothing() {
        service.notifyPasswordChanged(RECIPIENT, ActorContext.unknown(Instant.parse("2026-07-27T14:32:00Z")));

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.text()).contains("Time:").doesNotContain("Ip:").doesNotContain("Client:");
    }

    @Test
    void everySecurityNotice_closesWithTheCompromiseWarningAndCannotBeTurnedOff() {
        service.notifyRecoveryCodeUsed(RECIPIENT, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(TestMail.unwrapped(content))
                .contains("If this wasn't you, your account may be compromised.")
                .contains("Security notices cannot be turned off.")
                .doesNotContain("Notification settings");
    }

    /**
     * Wharf has no password reset by construction — the recovery code is the only way back in.
     * A mail that implied otherwise would be indistinguishable from a phishing attempt.
     */
    @Test
    void noSecurityNotice_offersAPasswordReset() {
        service.notifyRecoveryCodeUsed(RECIPIENT, ACTOR);
        service.notifyPasswordChanged(RECIPIENT, ACTOR);
        service.notifyPasswordSet(RECIPIENT, ACTOR);

        assertThat(mail.allSent()).allSatisfy(content ->
                assertThat(TestMail.unwrapped(content).toLowerCase()).doesNotContain("reset your password")
                        .doesNotContain("forgot your password"));
    }

    @Test
    void passwordSet_isDistinctFromPasswordChanged() {
        service.notifyPasswordSet(RECIPIENT, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.subject()).isEqualTo("A master password was set for your Wharf account");
        assertThat(TestMail.unwrapped(content))
                .contains("recovery code shown during setup is the only way back in");
    }

    @Test
    void devicePaired_reportsTheNameTheClientGaveItself() {
        service.notifyDevicePaired(RECIPIENT, "deniz laptop", ACTOR);

        assertThat(mail.sentTo(RECIPIENT).text()).contains("Device:").contains("deniz laptop");
    }

    @Test
    void devicePaired_omitsTheDeviceRowWhenTheClientDidNotNameItself() {
        service.notifyDevicePaired(RECIPIENT, null, ACTOR);

        assertThat(mail.sentTo(RECIPIENT).text()).doesNotContain("Device:");
    }

    @Test
    void providerLinked_namesTheProviderAndSaysItDoesNotUnlockTheVault() {
        service.notifyProviderLinked(RECIPIENT, OAuthProvider.GITHUB, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.subject()).isEqualTo("GitHub was linked to your Wharf account");
        assertThat(TestMail.unwrapped(content)).contains("does not unlock your vault");
    }

    /** Nothing to sign in to, and no recovery to imply — so no button at all. */
    @Test
    void accountDeleted_hasNoCallToAction() {
        service.notifyAccountDeleted(RECIPIENT, 3, ACTOR);

        MailContent content = mail.sentTo(RECIPIENT);
        assertThat(content.html()).doesNotContain("<a href");
        assertThat(TestMail.unwrapped(content))
                .contains("the 3 projects it owned")
                .contains("cannot be undone");
    }

    @Test
    void accountDeleted_readsCorrectlyForOneOrNoOwnedProjects() {
        service.notifyAccountDeleted(RECIPIENT, 1, ACTOR);
        service.notifyAccountDeleted("other@acme.io", 0, ACTOR);

        assertThat(TestMail.unwrapped(mail.allSent().get(0))).contains("the 1 project it owned");
        assertThat(TestMail.unwrapped(mail.allSent().get(1))).contains("its vault are gone");
    }

    @Test
    void deliveryFailure_isSwallowed() {
        doThrow(new RuntimeException("boom")).when(mail.port()).send(anyString(), any());

        assertThatCode(() -> service.notifyRecoveryCodeUsed(RECIPIENT, ACTOR)).doesNotThrowAnyException();
    }
}
