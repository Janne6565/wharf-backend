package com.wharf.backend.services.auth;

import com.wharf.backend.configuration.AsyncConfig;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.services.mail.ActorContext;
import com.wharf.backend.services.mail.MailDispatcher;
import com.wharf.backend.services.mail.MailSeverity;
import com.wharf.backend.services.mail.MailTemplate;
import com.wharf.backend.services.mail.MailTemplate.DetailRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The account-security notices: recovery used, password changed or first set, a device
 * paired, a sign-in provider linked, the account deleted.
 *
 * <p>These matter more here than in an ordinary product. Wharf has no password reset, no
 * support-side account recovery and no server-side session list — the design has no backdoor
 * by construction, and the only recovery path is a 40-character code shown once. That leaves
 * email as the sole out-of-band channel through which an account holder can learn that
 * someone else took control. Every notice therefore states plainly what happened, when, and
 * from where, and none of them can be turned off.
 *
 * <p>Each takes an {@link ActorContext} the caller resolved on the request thread; see
 * {@link com.wharf.backend.services.mail.ActorContextResolver} for why it is passed in rather
 * than looked up here. Delivery is best-effort throughout: a security notice that failed to
 * send must not roll back the security-relevant change it was reporting, because the change
 * has already happened and hiding it helps nobody.
 */
@Service
public class SecurityNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SecurityNotificationService.class);

    /** Closing line shared by every notice that reports access being gained or changed. */
    private static final String COMPROMISE_WARNING = "If this wasn't you, your account may be compromised.";

    private static final String CANNOT_BE_DISABLED = " Security notices cannot be turned off.";

    private final MailDispatcher mail;

    public SecurityNotificationService(MailDispatcher mail) {
        this.mail = mail;
    }

    /**
     * The recovery code was spent: the vault was re-encrypted, every session was revoked and
     * a fresh code was issued. The highest-severity mail in the system — this is exactly the
     * shape an account takeover takes, since it is the one path that does not require the
     * master password.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyRecoveryCodeUsed(String recipient, ActorContext actor) {
        deliver(recipient, MailTemplate.subject("Your Wharf recovery code was used")
                .strip(MailSeverity.DANGER, "SECURITY ALERT · RECOVERY CODE USED")
                .headline("Your recovery code was used to unlock your vault")
                .paragraph("All sessions were signed out and every paired terminal must sign in again. "
                        + "A new 40-character recovery code was issued; the previous one no longer works.")
                .paragraph("The new code is shown once, in the browser. Save it before you close the tab.")
                .details(actor.detailRows())
                .cta("Review your account", mail.accountUrl())
                .callout(COMPROMISE_WARNING)
                .footer("You got this because your Wharf recovery code was used." + CANNOT_BE_DISABLED));
    }

    /**
     * The master password changed on an account that already had one. Sessions survive
     * deliberately (a pairing is independent of the password), so the notice explains what
     * other devices will do rather than implying they were signed out.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyPasswordChanged(String recipient, ActorContext actor) {
        deliver(recipient, MailTemplate.subject("Your Wharf master password was changed")
                .strip(MailSeverity.WARNING, "SECURITY NOTICE · PASSWORD CHANGED")
                .headline("Your master password was changed")
                .paragraph("Your vault was re-encrypted with the new password. "
                        + "Other devices will ask for it the next time they sync.")
                .details(actor.detailRows())
                .cta("Review your account", mail.accountUrl())
                .callout(COMPROMISE_WARNING)
                .footer("You got this because your Wharf master password was changed." + CANNOT_BE_DISABLED));
    }

    /**
     * A master password was set for the first time on an account created through OAuth. Not
     * a password change — before this the account had no vault at all — so it gets its own
     * copy rather than reusing the change notice and confusing the reader about what existed.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyPasswordSet(String recipient, ActorContext actor) {
        deliver(recipient, MailTemplate.subject("A master password was set for your Wharf account")
                .strip(MailSeverity.WARNING, "SECURITY NOTICE · PASSWORD SET")
                .headline("A master password was set for your account")
                .paragraph("Your vault is now encrypted with it. Wharf never receives the password itself, "
                        + "so it cannot be reset — the recovery code shown during setup is the only way back in.")
                .details(actor.detailRows())
                .cta("Review your account", mail.accountUrl())
                .callout(COMPROMISE_WARNING)
                .footer("You got this because a master password was set on your Wharf account."
                        + CANNOT_BE_DISABLED));
    }

    /**
     * A device code was exchanged for a session — a terminal or the mobile app was paired.
     * {@code deviceName} is whatever the client called itself at exchange time and may be
     * absent; it is reported but never stored.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyDevicePaired(String recipient, String deviceName, ActorContext actor) {
        List<DetailRow> rows = new ArrayList<>(actor.detailRows());
        rows.add(new DetailRow("DEVICE", deviceName));

        deliver(recipient, MailTemplate.subject("A new device was paired with your Wharf account")
                .strip(MailSeverity.WARNING, "SECURITY NOTICE · DEVICE PAIRED")
                .headline("A new device was paired with your account")
                .paragraph("It can sync your vault once your master password is entered on it. "
                        + "Sign out everywhere from your account page to revoke it.")
                .details(rows)
                .cta("Review your account", mail.accountUrl())
                .callout(COMPROMISE_WARNING)
                .footer("You got this because a device was paired with your Wharf account." + CANNOT_BE_DISABLED));
    }

    /**
     * An external sign-in provider was linked to an <em>existing</em> account. Deliberately
     * not sent when a provider login creates a brand-new account: there, linking is the
     * registration, and there is no prior owner to warn.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyProviderLinked(String recipient, OAuthProvider provider, ActorContext actor) {
        String name = displayName(provider);
        List<DetailRow> rows = new ArrayList<>(actor.detailRows());
        rows.add(new DetailRow("PROVIDER", name));

        deliver(recipient, MailTemplate.subject(name + " was linked to your Wharf account")
                .strip(MailSeverity.WARNING, "SECURITY NOTICE · SIGN-IN METHOD ADDED")
                .headline(name + " was linked to your account")
                .paragraph("You can now sign in to Wharf with " + name + ". It does not unlock your vault — "
                        + "that still needs your master password.")
                .details(rows)
                .cta("Review sign-in methods", mail.accountUrl())
                .callout(COMPROMISE_WARNING)
                .footer("You got this because a sign-in method was added to your Wharf account."
                        + CANNOT_BE_DISABLED));
    }

    /**
     * The account is gone. No button: there is nothing left to sign in to, and offering a
     * link would imply a recovery that does not exist.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    public void notifyAccountDeleted(String recipient, int ownedProjectCount, ActorContext actor) {
        String projects = ownedProjectCount == 0
                ? ""
                : " and the " + ownedProjectCount + (ownedProjectCount == 1 ? " project" : " projects") + " it owned";

        deliver(recipient, MailTemplate.subject("Your Wharf account was deleted")
                .strip(MailSeverity.DANGER, "SECURITY ALERT · ACCOUNT DELETED")
                .headline("Your account was deleted")
                .paragraph(recipient + ", its vault" + projects + " are gone. Wharf holds no readable copy, "
                        + "so this cannot be undone or restored from a backup.")
                .paragraph("This is the last email you will get from us at this address.")
                .details(actor.detailRows())
                .callout(COMPROMISE_WARNING)
                .footer("You got this because the Wharf account for this address was deleted."));
    }

    private void deliver(String recipient, MailTemplate template) {
        try {
            mail.send(recipient, template);
        } catch (RuntimeException ex) {
            log.warn("Failed to send security notification to {}", recipient, ex);
        }
    }

    private static String displayName(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> "Google";
            case GITHUB -> "GitHub";
        };
    }
}
