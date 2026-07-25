package com.wharf.backend.services.auth;

import com.wharf.backend.client.MailPort;
import com.wharf.backend.configuration.AsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Sends the "here is your verification code" email off the request thread. Like the invite
 * notification, this lives in its own bean so the {@code @Async} proxy actually applies —
 * a self-invoked {@code @Async} method would run synchronously — and delivery is
 * best-effort: a failure is logged and swallowed rather than failing the registration that
 * triggered it (the user can always ask for a resend).
 *
 * <p>The mail is deliberately plain text and unbranded: a branded HTML template is a
 * separate piece of work, and plain text is what survives every client intact.
 */
@Service
public class EmailVerificationNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationNotificationService.class);

    private static final String SUBJECT = "Your Wharf verification code";

    private final MailPort mailPort;

    public EmailVerificationNotificationService(MailPort mailPort) {
        this.mailPort = mailPort;
    }

    /**
     * Send {@code code} to {@code recipient}. Runs asynchronously; never propagates a
     * failure to the caller.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void sendVerificationCode(String recipient, String code, Duration ttl) {
        try {
            mailPort.send(recipient, SUBJECT, body(code, ttl), false);
        } catch (RuntimeException ex) {
            // Never log the code itself — the log is not a place a live credential belongs.
            log.warn("Failed to send verification code to {}", recipient, ex);
        }
    }

    private static String body(String code, Duration ttl) {
        return "Your Wharf verification code is " + code + ".\n\n"
                + "It expires in " + ttl.toMinutes() + " minutes.\n\n"
                + "If you did not request this, ignore this email — no account action was taken.";
    }
}
