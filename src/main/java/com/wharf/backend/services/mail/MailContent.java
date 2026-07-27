package com.wharf.backend.services.mail;

/**
 * A rendered message: subject plus both representations of the same body.
 *
 * <p>Both are always produced. The HTML is what most readers see; the plain text is not a
 * lossy afterthought but a full counterpart, because these are transactional and
 * security-critical mails — a client that refuses HTML, a terminal mail reader, or a screen
 * reader falling back to the text part must still get the complete message, including the
 * verification code and the event details.
 */
public record MailContent(String subject, String html, String text) {
}
