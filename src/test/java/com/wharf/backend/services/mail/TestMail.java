package com.wharf.backend.services.mail;

import com.wharf.backend.client.MailPort;
import com.wharf.backend.configuration.MailProperties;
import com.wharf.backend.services.user.NotificationPreferencesService;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A real {@link MailDispatcher} over a mocked {@link MailPort}, so notification tests assert
 * against the message that would actually be delivered rather than against the arguments of a
 * mocked dispatcher. Rendering is the part of a notification most likely to be wrong — an
 * unescaped project name, a detail row silently dropped — and a mocked dispatcher would prove
 * none of it.
 */
public final class TestMail {

    public static final String BASE_URL = "https://wharf.example";
    public static final String DOMAIN = "wharf.example";

    private final MailPort port = mock(MailPort.class);
    private final MailDispatcher dispatcher =
            new MailDispatcher(port, new MailProperties("https://mail.invalid", "", BASE_URL, DOMAIN));

    private final NotificationPreferencesService preferences = mock(NotificationPreferencesService.class);

    /**
     * A preferences service that answers "yes" to everything, so a test asserting on rendering
     * is not also silently asserting on opt-out state. Tests about opting out re-stub it.
     */
    public NotificationPreferencesService preferences() {
        org.mockito.Mockito.lenient()
                .when(preferences.isEnabled(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        return preferences;
    }

    public MailDispatcher dispatcher() {
        return dispatcher;
    }

    public MailPort port() {
        return port;
    }

    /** The single message sent to {@code recipient}; fails if there was not exactly one. */
    public MailContent sentTo(String recipient) {
        ArgumentCaptor<MailContent> captor = ArgumentCaptor.forClass(MailContent.class);
        verify(port).send(org.mockito.ArgumentMatchers.eq(recipient), captor.capture());
        return captor.getValue();
    }

    /**
     * The plain-text body with its wrapping undone, so a test can assert on a sentence
     * without caring which column it happened to break at.
     */
    public static String unwrapped(MailContent content) {
        return content.text().replaceAll("\\s+", " ");
    }

    /** Every message sent, in order, regardless of recipient. */
    public List<MailContent> allSent() {
        ArgumentCaptor<MailContent> captor = ArgumentCaptor.forClass(MailContent.class);
        verify(port, org.mockito.Mockito.atLeastOnce())
                .send(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        return captor.getAllValues();
    }
}
