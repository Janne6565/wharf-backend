package com.wharf.backend.client;

import com.wharf.backend.services.mail.MailContent;

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
     * Deliver a single message as {@code multipart/alternative}. Never throws — failures are
     * logged and dropped.
     *
     * @param recipient the destination address
     * @param content   the rendered subject and both body representations
     */
    void send(String recipient, MailContent content);
}
