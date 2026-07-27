package com.wharf.backend.services.mail;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who did the thing, as far as the request itself revealed: when, from which IP, with which
 * client.
 *
 * <p>None of this is persisted. It is read off the live request on the request thread and
 * carried into the notification as a value, which is the whole reason security notices can
 * name an IP and a client without the server growing an audit log of its users' movements —
 * a poor fit for a product whose entire premise is that the server knows as little as
 * possible. The cost is that these details exist only in the mail: there is no
 * "recent activity" list to check afterwards, which is why every alert asks the reader to
 * act on the message in front of them.
 *
 * <p>Location is deliberately absent. Reporting one would mean shipping a GeoIP database
 * and resolving every actor's address against it; the design permits omitting the row, and
 * an absent row is better than a guess the reader might trust.
 *
 * <p>Any field may be {@code null} — background jobs and tests have no request — and null
 * fields simply drop their row from the detail table.
 */
public record ActorContext(Instant at, String ip, String client) {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** An unknown actor: the event is still reported, with its timestamp and nothing else. */
    public static ActorContext unknown(Instant at) {
        return new ActorContext(at, null, null);
    }

    /**
     * The standard TIME / IP / CLIENT rows shared by every security notice, in that order.
     * Callers prepend or append their own event-specific rows.
     */
    public List<MailTemplate.DetailRow> detailRows() {
        List<MailTemplate.DetailRow> rows = new ArrayList<>(3);
        rows.add(new MailTemplate.DetailRow("TIME", timestamp()));
        rows.add(new MailTemplate.DetailRow("IP", ip));
        rows.add(new MailTemplate.DetailRow("CLIENT", client));
        return rows;
    }

    /** Absolute UTC, never relative: "in 2 hours" is meaningless in a mail read days later. */
    public String timestamp() {
        return at == null ? null : TIMESTAMP.format(at);
    }
}
