package com.wharf.backend.services.mail;

import com.wharf.backend.client.MailPort;
import com.wharf.backend.configuration.MailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single seam between a composed {@link MailTemplate} and the outbound port: stamps the
 * configured sending domain onto every message, hands it to the transport, and guarantees
 * the caller sees no failure.
 *
 * <p>Also owns the deep links mails point at, so the set of destinations is enumerated in one
 * place. That matters more than it looks: a link in an email cannot be refactored once sent,
 * so every URL here must be a route that exists and will keep existing.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final MailPort mailPort;
    private final MailProperties properties;

    public MailDispatcher(MailPort mailPort, MailProperties properties) {
        this.mailPort = mailPort;
        this.properties = properties;
    }

    /**
     * Render and deliver. {@link MailPort} implementations already swallow their own delivery
     * failures; the catch here covers the rendering step as well, so a malformed template can
     * never propagate out of a notification and abort the operation that triggered it.
     */
    public void send(String recipient, MailTemplate template) {
        try {
            mailPort.send(recipient, template.sendingDomain(properties.sendingDomain()).build());
        } catch (RuntimeException ex) {
            log.warn("Failed to render or deliver mail to {}", recipient, ex);
        }
    }

    /** The bare sending domain, for footer sentences that name it in prose. */
    public String domain() {
        return properties.sendingDomain();
    }

    /** Account settings: sign-in methods, password, recovery. Where security notices send readers. */
    public String accountUrl() {
        return properties.url("/account");
    }

    /** Project list, which is also where incoming invites appear. */
    public String projectsUrl() {
        return properties.url("/projects");
    }

    public String projectUrl(UUID projectId) {
        return properties.url("/projects/" + projectId);
    }

    /**
     * Where collaboration mail points its "Notification settings" link — the screen holding
     * the opt-out for the very mail the reader is looking at.
     *
     * <p>Security notices never call this. Their footers state that they cannot be turned off,
     * and there is no key, column or control that could.
     */
    public String notificationSettingsUrl() {
        return properties.url("/account/notifications");
    }
}
