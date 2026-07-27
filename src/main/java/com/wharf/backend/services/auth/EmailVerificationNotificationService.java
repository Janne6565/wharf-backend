package com.wharf.backend.services.auth;

import com.wharf.backend.configuration.AsyncConfig;
import com.wharf.backend.services.mail.MailDispatcher;
import com.wharf.backend.services.mail.MailTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Sends the "here is your verification code" email off the request thread. Like the other
 * notification services this lives in its own bean so the {@code @Async} proxy actually
 * applies — a self-invoked {@code @Async} method would run synchronously — and delivery is
 * best-effort: a failure is logged and swallowed rather than failing the registration that
 * triggered it (the user can always ask for a resend).
 */
@Service
public class EmailVerificationNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationNotificationService.class);

    private final MailDispatcher mail;

    public EmailVerificationNotificationService(MailDispatcher mail) {
        this.mail = mail;
    }

    /**
     * Send {@code code} to {@code recipient}. Runs asynchronously; never propagates a
     * failure to the caller.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void sendVerificationCode(String recipient, String code, Duration ttl) {
        try {
            // The code leads the subject line so it can be read from a notification banner
            // without opening the mail at all — the common case on a phone.
            mail.send(recipient, MailTemplate.subject(code + " is your Wharf verification code")
                    .headline("Your verification code")
                    .paragraph("Enter this code in the browser tab that asked for it. It works once.")
                    .codePanel(code, "expires in " + ttl.toMinutes() + " minutes")
                    // No status strip and no button. This mail asks for a code to be typed
                    // back into a tab the reader already has open, so a link would only invite
                    // them to start a second flow they cannot tell apart from a phishing one.
                    .paragraph("If you did not ask for a code, no action is needed. "
                            + "The code alone cannot decrypt your vault.")
                    .footer("You got this because " + recipient + " was entered on " + mail.domain() + "."));
        } catch (RuntimeException ex) {
            // Never log the code itself — the log is not a place a live credential belongs.
            log.warn("Failed to send verification code to {}", recipient, ex);
        }
    }
}
