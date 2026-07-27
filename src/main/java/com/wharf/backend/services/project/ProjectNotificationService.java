package com.wharf.backend.services.project;

import com.wharf.backend.configuration.AsyncConfig;
import com.wharf.backend.model.core.NotificationPreference;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.services.mail.ActorContext;
import com.wharf.backend.services.mail.MailDispatcher;
import com.wharf.backend.services.mail.MailSeverity;
import com.wharf.backend.services.mail.MailTemplate;
import com.wharf.backend.services.mail.MailTemplate.DetailRow;
import com.wharf.backend.services.user.NotificationPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Membership mail: a project key arriving, a role changing, access ending, a project going
 * away.
 *
 * <p>These are styled calmly — no alert strip, no compromise warning — because none of them
 * indicate anything wrong. They exist because the alternative is silence: the accept-then-
 * finalize key flow means an invitee who has joined still cannot read anything until an admin
 * seals the project key on a later pass, and removal is only expressible through a key
 * rotation, so without a notification a member simply watches their hosts disappear with no
 * explanation. Each one names the admin who acted and sends questions to them rather than to
 * us, since a zero-knowledge server has no view into why a team changed its membership.
 */
@Service
public class ProjectNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ProjectNotificationService.class);

    private final MailDispatcher mail;
    private final NotificationPreferencesService preferences;

    public ProjectNotificationService(MailDispatcher mail, NotificationPreferencesService preferences) {
        this.mail = mail;
        this.preferences = preferences;
    }

    /**
     * An admin sealed the project DEK to this member's public key, so the shared hosts now
     * decrypt on their devices. This is the moment membership becomes real, which is why it
     * gets a mail of its own rather than being folded into the accept.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyAccessGranted(String recipient, String projectName, UUID projectId,
                                    ProjectRole role, String grantedByEmail) {
        deliver(recipient, NotificationPreference.PROJECT_ACCESS_GRANTED,
                MailTemplate.subject("You have access to " + projectName)
                .strip(MailSeverity.NEUTRAL, "PROJECT ACCESS GRANTED")
                .headline("You now have access to " + projectName)
                .paragraph("The project's vault key was encrypted to your account, so its hosts decrypt on "
                        + "your devices. Run wharf sync in your terminal to pull them.", "wharf sync")
                .details(List.of(
                        new DetailRow("PROJECT", projectName),
                        new DetailRow("YOUR ROLE", label(role)),
                        new DetailRow("GRANTED BY", grantedByEmail)))
                .cta("Open project", mail.projectUrl(projectId))
                .footer("You got this because you were granted access to a Wharf project.")
                .notificationSettings(mail.notificationSettingsUrl()));
    }

    /**
     * Access ended. No CTA: there is nothing left to open, and a button pointing at a project
     * the reader can no longer see would be a dead end.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyRemoved(String recipient, String projectName, String actorEmail, ActorContext actor) {
        deliver(recipient, NotificationPreference.PROJECT_ACCESS_REMOVED,
                MailTemplate.subject("You were removed from " + projectName)
                .headline("You were removed from " + projectName)
                .paragraph("The project's hosts are dropped from your vault on the next sync. "
                        + "Your own hosts, keys and other projects are unchanged.")
                .details(List.of(
                        new DetailRow("PROJECT", projectName),
                        new DetailRow("CHANGED BY", actorEmail),
                        new DetailRow("TIME", actor.timestamp())))
                .note("Questions about this go to the project admin, not to us.",
                        "Your projects", mail.projectsUrl())
                .footer("You got this because your membership of a Wharf project changed.")
                .notificationSettings(mail.notificationSettingsUrl()));
    }

    /** A role change, including the ownership transfer that demotes the previous owner. */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyRoleChanged(String recipient, String projectName, UUID projectId,
                                  ProjectRole previousRole, ProjectRole newRole,
                                  String actorEmail, ActorContext actor) {
        deliver(recipient, NotificationPreference.ROLE_CHANGED,
                MailTemplate.subject("Your role in " + projectName + " is now " + label(newRole))
                .headline("Your role in " + projectName + " changed")
                .paragraph("You are now " + article(newRole) + " " + label(newRole) + ". " + capability(newRole))
                .details(List.of(
                        new DetailRow("PROJECT", projectName),
                        new DetailRow("ROLE", label(previousRole) + " → " + label(newRole)),
                        new DetailRow("CHANGED BY", actorEmail),
                        new DetailRow("TIME", actor.timestamp())))
                .cta("Open project", mail.projectUrl(projectId))
                .footer("You got this because your membership of a Wharf project changed.")
                .notificationSettings(mail.notificationSettingsUrl()));
    }

    /**
     * The project is gone, for every former member. Also sent when an owner deletes their
     * account, which destroys the projects they owned along with it — from the members' side
     * those two are the same event, so they get the same mail.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyProjectDeleted(String recipient, String projectName,
                                     String actorEmail, ActorContext actor) {
        deliver(recipient, NotificationPreference.PROJECT_DELETED,
                MailTemplate.subject(projectName + " was deleted")
                .headline(projectName + " was deleted")
                .paragraph("Its shared hosts were removed from every member's vault, including yours. "
                        + "Hosts you added to your own vault separately are not affected.")
                .details(List.of(
                        new DetailRow("PROJECT", projectName),
                        new DetailRow("DELETED BY", actorEmail),
                        new DetailRow("TIME", actor.timestamp())))
                .note("Deleted projects are not recoverable.", "Your projects", mail.projectsUrl())
                .footer("You got this because you were a member of this Wharf project.")
                .notificationSettings(mail.notificationSettingsUrl()));
    }

    /**
     * Send unless the recipient has switched this notification off. Centralised here so no
     * future notification can be added without passing through the opt-out check.
     */
    private void deliver(String recipient, NotificationPreference preference, MailTemplate template) {
        try {
            if (!preferences.isEnabled(recipient, preference)) {
                log.debug("Skipping {} notification: recipient has it switched off", preference.key());
                return;
            }
            mail.send(recipient, template);
        } catch (RuntimeException ex) {
            log.warn("Failed to send project notification to {}", recipient, ex);
        }
    }

    private static String label(ProjectRole role) {
        return role.name().toLowerCase(Locale.ENGLISH);
    }

    /** A project has exactly one owner, so that role takes the definite article. */
    private static String article(ProjectRole role) {
        return switch (role) {
            case MEMBER -> "a";
            case ADMIN -> "an";
            case OWNER -> "the";
        };
    }

    private static String capability(ProjectRole role) {
        return switch (role) {
            case MEMBER -> "You can read and edit the project's hosts.";
            case ADMIN -> "You can also manage members, invites and project keys.";
            case OWNER -> "You can also transfer ownership and delete the project.";
        };
    }
}
