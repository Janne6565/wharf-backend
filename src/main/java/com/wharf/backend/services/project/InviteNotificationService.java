package com.wharf.backend.services.project;

import com.wharf.backend.configuration.AsyncConfig;
import com.wharf.backend.model.core.NotificationPreference;
import com.wharf.backend.services.mail.MailDispatcher;
import com.wharf.backend.services.mail.MailSeverity;
import com.wharf.backend.services.mail.MailTemplate;
import com.wharf.backend.services.mail.MailTemplate.DetailRow;
import com.wharf.backend.services.user.NotificationPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Invite mail: the "you've been invited" notice to the invitee, and the answer that goes back
 * to whoever sent it.
 *
 * <p>These live in their own bean (rather than as private methods on
 * {@link ProjectInviteService}) so the {@code @Async} proxy actually applies — a self-invoked
 * {@code @Async} method would run synchronously. Delivery is best-effort: any failure is
 * logged and swallowed so it can never affect the invite that triggered it.
 *
 * <p>The invite notice is the only mail in the system that can reach an address with no Wharf
 * account behind it, which makes it the one piece of genuinely cold mail we send. It is
 * therefore the most conservative template: it explains what Wharf is, names who invited them
 * and when the invite lapses, and asks for nothing beyond a single click.
 */
@Service
public class InviteNotificationService {

    private static final Logger log = LoggerFactory.getLogger(InviteNotificationService.class);

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final MailDispatcher mail;
    private final NotificationPreferencesService preferences;

    public InviteNotificationService(MailDispatcher mail, NotificationPreferencesService preferences) {
        this.mail = mail;
        this.preferences = preferences;
    }

    /**
     * Notify {@code recipient} that they were invited to {@code projectName} by
     * {@code inviterEmail}. Runs asynchronously; never propagates a failure to the caller.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyInvited(String recipient, String inviterEmail, String projectName, Instant expiresAt) {
        // The one recipient who may have no account at all — an address that has expressed no
        // preference has not opted out, so a stranger can still be invited.
        deliver(recipient, NotificationPreference.PROJECT_INVITE,
                MailTemplate.subject(inviterEmail + " invited you to " + projectName + " on Wharf")
                .strip(MailSeverity.NEUTRAL, "PROJECT INVITE")
                .headline(inviterEmail + " invited you to " + projectName)
                .paragraph("Accepting adds the project's hosts to your Wharf vault. "
                        + "You will be asked to create an account if you do not have one.")
                .paragraph("Wharf is a terminal SSH client that syncs your hosts and keys between devices, "
                        + "encrypted so that only you can read them.")
                .details(List.of(
                        new DetailRow("PROJECT", projectName),
                        new DetailRow("INVITED BY", inviterEmail),
                        new DetailRow("EXPIRES", EXPIRY_FORMAT.format(expiresAt))))
                // One link, to the project list where the invite is waiting. There is no
                // accept-by-link: accepting is an authenticated action, and a one-click accept
                // from a mail would hand project access to whoever holds the inbox.
                .cta("Open Wharf", mail.projectsUrl())
                .note("Not expecting this? Ignore this email — the invite lapses on its own.", null, null)
                .footer("You got this because " + inviterEmail
                        + " entered your address when inviting you to a Wharf project."));
    }

    /** Tell the inviter their invite was taken up, so admins need not poll the members list. */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyInviteAccepted(String inviterEmail, String inviteeEmail,
                                     String projectName, UUID projectId, long memberCount) {
        deliver(inviterEmail, NotificationPreference.INVITE_ACCEPTED,
                MailTemplate.subject(inviteeEmail + " accepted your invite to " + projectName)
                .headline(inviteeEmail + " accepted your invite")
                .paragraph("They joined " + projectName + " as a member. They cannot read the project's hosts "
                        + "until an admin seals the project key to their account.")
                .details(List.of(
                        new DetailRow("PROJECT", projectName),
                        new DetailRow("MEMBER", inviteeEmail),
                        new DetailRow("MEMBERS", String.valueOf(memberCount))))
                .cta("Open project", mail.projectUrl(projectId))
                .footer("You got this because you invited " + inviteeEmail + " to a project you administer.")
                .notificationSettings(mail.notificationSettingsUrl()));
    }

    /** The same, for a declined invite. No member row: nobody joined. */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyInviteDeclined(String inviterEmail, String inviteeEmail,
                                     String projectName, UUID projectId, long memberCount) {
        deliver(inviterEmail, NotificationPreference.INVITE_DECLINED,
                MailTemplate.subject(inviteeEmail + " declined your invite to " + projectName)
                .headline(inviteeEmail + " declined your invite")
                .paragraph("The invite is closed and no access was granted. You can send a new one at any time.")
                .details(List.of(
                        new DetailRow("PROJECT", projectName),
                        new DetailRow("MEMBERS", String.valueOf(memberCount))))
                .cta("Open project", mail.projectUrl(projectId))
                .footer("You got this because you invited " + inviteeEmail + " to a project you administer.")
                .notificationSettings(mail.notificationSettingsUrl()));
    }

    /**
     * Send unless the recipient has switched this notification off. The check lives here rather
     * than at the call sites so no future caller can forget it, and it runs on the mail executor
     * — the preference read is one indexed lookup and must not sit on the request thread.
     */
    private void deliver(String recipient, NotificationPreference preference, MailTemplate template) {
        try {
            if (!preferences.isEnabled(recipient, preference)) {
                log.debug("Skipping {} notification: recipient has it switched off", preference.key());
                return;
            }
            mail.send(recipient, template);
        } catch (RuntimeException ex) {
            log.warn("Failed to send invite notification to {}", recipient, ex);
        }
    }
}
