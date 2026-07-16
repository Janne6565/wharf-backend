package com.wharf.backend.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op {@link MailPort} wired in when no mail API key is configured (e.g. local dev and
 * tests). It drops the message after a {@code DEBUG} log, so features that send mail work
 * unchanged without an outbound dependency.
 */
public class NoopMailClient implements MailPort {

    private static final Logger log = LoggerFactory.getLogger(NoopMailClient.class);

    @Override
    public void send(String recipient, String subject, String body, boolean html) {
        log.debug("Mail delivery not configured; dropping message to {} (subject: {})", recipient, subject);
    }
}
