package com.wharf.backend.services.mail;

/**
 * How loud a notification is allowed to be. The design system carries severity entirely
 * through colour and words — a 3px left rule on the status strip, the strip's own palette
 * and the strip text — because Gmail strips SVG and Outlook's Word rendering engine cannot
 * draw it, so an icon-based severity cue would simply vanish for a large share of readers.
 *
 * <p>The palette values are the mid-tone brand colours rather than the neons used in the
 * app: all three remain legible on white if a mail client discards our backgrounds.
 */
public enum MailSeverity {

    /**
     * Informational. Collaboration mail and the project invite. Its border is the card's own
     * hairline rather than the heavier panel border the other two use — a neutral strip is a
     * label, not a frame, and should not read as a boundary inside the card.
     */
    NEUTRAL("#10161d", "#1a232d", "#57d7c2", "#8ae8d8", "#233140"),

    /** Something security-relevant happened that the account holder should recognise. */
    WARNING("#171308", "#4a3c22", "#e3c078", "#e3c078", "#233140"),

    /** Irreversible or access-granting. The reader is expected to act if it wasn't them. */
    DANGER("#170b0a", "#5a2a28", "#e0685e", "#e0685e", "#5a2a28");

    private final String background;
    private final String border;
    private final String rule;
    private final String text;
    private final String cardBorder;

    MailSeverity(String background, String border, String rule, String text, String cardBorder) {
        this.background = background;
        this.border = border;
        this.rule = rule;
        this.text = text;
        this.cardBorder = cardBorder;
    }

    /** Fill behind the status strip and the matching closing callout. */
    public String background() {
        return background;
    }

    /** Hairline around the strip and callout. */
    public String border() {
        return border;
    }

    /** The 3px left rule that actually carries the severity. */
    public String rule() {
        return rule;
    }

    /** Strip and callout text colour. */
    public String text() {
        return text;
    }

    /**
     * Border of the 600px card itself. Only DANGER escalates this far — a warning is
     * contained within the strip, an alert frames the whole message.
     */
    public String cardBorder() {
        return cardBorder;
    }
}
