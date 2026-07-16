package com.wharf.backend.services.project;

import com.wharf.backend.client.MailPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InviteNotificationServiceTest {

    private final MailPort mailPort = mock(MailPort.class);
    private final InviteNotificationService service = new InviteNotificationService(mailPort);

    @Test
    void notifyInvited_sendsPlainTextMailWithInviterAndProject() {
        Instant expiresAt = Instant.parse("2026-07-30T12:00:00Z");

        service.notifyInvited("invitee@acme.io", "admin@acme.io", "Acme", expiresAt);

        verify(mailPort).send(
                eq("invitee@acme.io"),
                eq("You've been invited to \"Acme\" on Wharf"),
                contains("admin@acme.io invited you to the project \"Acme\" on Wharf"),
                eq(false));
    }

    @Test
    void notifyInvited_mailPortThrows_isSwallowed() {
        doThrow(new RuntimeException("boom")).when(mailPort)
                .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());

        assertThatCode(() -> service.notifyInvited("invitee@acme.io", "admin@acme.io", "Acme", Instant.now()))
                .doesNotThrowAnyException();
    }
}
