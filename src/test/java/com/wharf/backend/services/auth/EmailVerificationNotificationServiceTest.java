package com.wharf.backend.services.auth;

import com.wharf.backend.services.mail.MailContent;
import com.wharf.backend.services.mail.TestMail;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

class EmailVerificationNotificationServiceTest {

    private final TestMail mail = new TestMail();
    private final EmailVerificationNotificationService service =
            new EmailVerificationNotificationService(mail.dispatcher());

    @Test
    void sendVerificationCode_leadsTheSubjectWithTheCode() {
        service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15));

        assertThat(mail.sentTo("deniz@acme.io").subject()).isEqualTo("042042 is your Wharf verification code");
    }

    @Test
    void sendVerificationCode_carriesCodeAndExpiryInBothRepresentations() {
        service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15));

        MailContent content = mail.sentTo("deniz@acme.io");
        assertThat(content.html()).contains("042042").contains("expires in 15 minutes");
        assertThat(content.text()).contains("042042").contains("Expires in 15 minutes");
    }

    @Test
    void sendVerificationCode_tellsUnexpectedRecipientsToIgnoreIt() {
        service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15));

        assertThat(mail.sentTo("deniz@acme.io").text()).contains("no action is needed");
    }

    /**
     * The code is a live credential, so the mail must not offer a link that could be
     * mistaken for a one-click sign-in — the reader is meant to type it into the tab they
     * already have open.
     */
    @Test
    void sendVerificationCode_containsNoLinks() {
        service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15));

        assertThat(mail.sentTo("deniz@acme.io").html()).doesNotContain("<a href");
    }

    @Test
    void sendVerificationCode_mailPortThrows_isSwallowed() {
        doThrow(new RuntimeException("boom")).when(mail.port()).send(anyString(), any());

        assertThatCode(() -> service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15)))
                .doesNotThrowAnyException();
    }
}
