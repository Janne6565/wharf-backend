package com.wharf.backend.services.project;

import com.wharf.backend.client.MailPort;
import com.wharf.backend.configuration.AsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sends the "you've been invited" notification email off the request thread. This lives in
 * its own bean (rather than a private method on {@link ProjectInviteService}) so the
 * {@code @Async} proxy actually applies — a self-invoked {@code @Async} method would run
 * synchronously. Delivery is best-effort: any failure is logged and swallowed so it can
 * never affect the invite that triggered it.
 */
@Service
public class InviteNotificationService {

    private static final Logger log = LoggerFactory.getLogger(InviteNotificationService.class);

    private static final String SIGN_IN_URL = "https://wharf.jannekeipert.de";
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final MailPort mailPort;

    public InviteNotificationService(MailPort mailPort) {
        this.mailPort = mailPort;
    }

    /**
     * Notify {@code recipient} that they were invited to {@code projectName} by
     * {@code inviterEmail}. Runs asynchronously; never propagates a failure to the caller.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyInvited(String recipient, String inviterEmail, String projectName, Instant expiresAt) {
        try {
            mailPort.send(recipient, subject(projectName), body(inviterEmail, projectName, expiresAt), false);
        } catch (RuntimeException ex) {
            log.warn("Failed to send invite notification to {}", recipient, ex);
        }
    }

    private static String subject(String projectName) {
        return "You've been invited to \"" + projectName + "\" on Wharf";
    }

    private static String body(String inviterEmail, String projectName, Instant expiresAt) {
        return inviterEmail + " invited you to the project \"" + projectName + "\" on Wharf.\n\n"
                + "Sign in at " + SIGN_IN_URL + " — the invite appears in your account "
                + "(also in the terminal app). It expires " + EXPIRY_FORMAT.format(expiresAt) + ".";
    }
}
