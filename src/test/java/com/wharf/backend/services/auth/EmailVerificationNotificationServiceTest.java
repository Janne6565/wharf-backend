package com.wharf.backend.services.auth;

import com.wharf.backend.client.MailPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailVerificationNotificationServiceTest {

    private final MailPort mailPort = mock(MailPort.class);
    private final EmailVerificationNotificationService service =
            new EmailVerificationNotificationService(mailPort);

    @Test
    void sendVerificationCode_sendsPlainTextMailWithCodeAndExpiry() {
        service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15));

        verify(mailPort).send(
                eq("deniz@acme.io"),
                eq("Your Wharf verification code"),
                contains("042042"),
                eq(false));
    }

    @Test
    void sendVerificationCode_bodyStatesExpiryAndWhatToDoIfUnexpected() {
        service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15));

        verify(mailPort).send(any(), any(), contains("expires in 15 minutes"), anyBoolean());
        verify(mailPort).send(any(), any(), contains("ignore this email"), anyBoolean());
    }

    @Test
    void sendVerificationCode_mailPortThrows_isSwallowed() {
        doThrow(new RuntimeException("boom")).when(mailPort).send(any(), any(), any(), anyBoolean());

        assertThatCode(() -> service.sendVerificationCode("deniz@acme.io", "042042", Duration.ofMinutes(15)))
                .doesNotThrowAnyException();
    }
}
