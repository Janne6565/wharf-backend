package com.wharf.backend.client;

/**
 * Outbound port for transactional email. The application talks to an external mail
 * delivery service through this interface only, so business code never depends on the
 * concrete transport (see {@link MailServiceClient}) or on whether delivery is even
 * configured ({@link NoopMailClient} is wired in when it is not).
 *
 * <p>Implementations are <em>best-effort</em>: {@code send} must never throw. A delivery
 * failure is logged and swallowed so it cannot affect the caller's own operation.
 */
public interface MailPort {

    /**
     * Deliver a single message. Never throws — failures are logged and dropped.
     *
     * @param recipient the destination email address
     * @param subject   the message subject
     * @param body      the message body (plain text or HTML depending on {@code html})
     * @param html      {@code true} to render the body as HTML, {@code false} for plain text
     */
    void send(String recipient, String subject, String body, boolean html);
}
