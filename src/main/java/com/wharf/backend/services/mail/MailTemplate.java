package com.wharf.backend.services.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builder for the one email shell every Wharf notification uses, rendering it to both HTML
 * and plain text in a single pass so the two can never drift apart.
 *
 * <p>The HTML is deliberately archaic. Email clients are not browsers: Outlook renders
 * through Word, Gmail rewrites the document, and several strip whole categories of markup.
 * So the output is nested tables with inline styles at a fixed 600px, vertical rhythm comes
 * from spacer divs rather than margins between blocks, and every cell that carries colour
 * declares it as both a {@code bgcolor} attribute and a {@code background-color} style. No
 * external CSS, no web fonts, no JavaScript, no SVG, no background images, no gradients —
 * which is also what keeps the design legible if a client forces its own light theme, since
 * each colour pair is declared on the same cell and inverts together.
 *
 * <p>Everything interpolated into the body is HTML-escaped. Project names, device labels and
 * email addresses are user-controlled strings, and a notification is the one place in a
 * zero-knowledge product where attacker-chosen text reaches a third party's inbox.
 *
 * @see MailSeverity for how severity is expressed without icons
 */
public final class MailTemplate {

    private static final String MONO = "ui-monospace,'SFMono-Regular',Menlo,monospace";
    private static final String SANS = "-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif";

    private static final String PAGE_BG = "#0a0e13";
    private static final String CARD_BG = "#0c1219";
    private static final String PANEL_BG = "#10161d";
    private static final String HAIRLINE = "#1a232d";
    private static final String BORDER = "#233140";
    private static final String TEXT = "#e8edf2";
    private static final String MUTED = "#8b99a5";
    private static final String DIM = "#54646f";
    private static final String ACCENT = "#57d7c2";
    private static final String ACCENT_INK = "#07110e";

    /** Where the plain-text counterpart wraps. Comfortably inside an 80-column terminal. */
    private static final int TEXT_WIDTH = 68;

    private final String subject;
    private final List<Block> blocks = new ArrayList<>();

    private String headline;
    private MailSeverity severity;
    private String stripLabel;
    private String footerWhy;
    private String notificationSettingsUrl;
    private String sendingDomain = "wharf.jannekeipert.de";

    private MailTemplate(String subject) {
        this.subject = subject;
    }

    public static MailTemplate subject(String subject) {
        return new MailTemplate(subject);
    }

    /**
     * Add the coloured status strip under the wordmark. Use " · " inside the label to
     * separate the category from the specific event ("SECURITY ALERT · RECOVERY CODE USED");
     * the plain-text header turns that into a colon.
     */
    public MailTemplate strip(MailSeverity severity, String label) {
        this.severity = severity;
        this.stripLabel = label;
        return this;
    }

    /** The single-sentence h1. Required. */
    public MailTemplate headline(String headline) {
        this.headline = headline;
        return this;
    }

    public MailTemplate paragraph(String text) {
        blocks.add(new Paragraph(text, null));
        return this;
    }

    /**
     * A paragraph in which every occurrence of {@code codeTerm} is set in the monospace face
     * — used for literal commands such as {@code wharf sync} so they read as something to
     * type rather than as prose.
     */
    public MailTemplate paragraph(String text, String codeTerm) {
        blocks.add(new Paragraph(text, codeTerm));
        return this;
    }

    /**
     * The large, selectable code panel. The value is always real text, never an image: a
     * code the reader cannot copy (or that a blocked-images client hides entirely) is not a
     * code at all.
     */
    public MailTemplate codePanel(String code, String caption) {
        blocks.add(new CodePanel(code, caption));
        return this;
    }

    /** The label/value table. Rows whose value is {@code null} or blank are dropped. */
    public MailTemplate details(List<DetailRow> rows) {
        List<DetailRow> present = rows.stream()
                .filter(row -> row.value() != null && !row.value().isBlank())
                .toList();
        if (!present.isEmpty()) {
            blocks.add(new Details(present));
        }
        return this;
    }

    /** The single primary button. At most one per mail; several templates have none. */
    public MailTemplate cta(String label, String url) {
        blocks.add(new Cta(label, url));
        return this;
    }

    /** A closing sentence, optionally carrying one secondary link. */
    public MailTemplate note(String text, String linkLabel, String linkUrl) {
        blocks.add(new Note(text, linkLabel, linkUrl));
        return this;
    }

    /** The severity-coloured closing box. Requires {@link #strip} to have set a severity. */
    public MailTemplate callout(String text) {
        blocks.add(new Callout(text));
        return this;
    }

    /**
     * The mandatory "why you got this" footer line. Security notices state that they cannot
     * be turned off and pass no settings URL; collaboration mail passes one.
     */
    public MailTemplate footer(String why) {
        this.footerWhy = why;
        return this;
    }

    public MailTemplate notificationSettings(String url) {
        this.notificationSettingsUrl = url;
        return this;
    }

    public MailTemplate sendingDomain(String domain) {
        this.sendingDomain = domain;
        return this;
    }

    public MailContent build() {
        return new MailContent(subject, renderHtml(), renderText());
    }

    // ---------------------------------------------------------------- HTML

    private String renderHtml() {
        MailSeverity level = severity == null ? MailSeverity.NEUTRAL : severity;
        String cardBorder = severity == null ? BORDER : level.cardBorder();

        StringBuilder out = new StringBuilder(4096);
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(subject)).append("</title>\n")
                .append("</head>\n")
                .append("<body style=\"margin:0;padding:0;background-color:").append(PAGE_BG).append("\">\n");

        // Outer table paints the page colour; the inner one is the fixed-width card. Two
        // levels are the minimum that survives clients which ignore body background.
        out.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(PAGE_BG)
                .append("\" style=\"background-color:").append(PAGE_BG).append(";border-collapse:collapse\">")
                .append("<tbody><tr><td align=\"center\" style=\"padding:28px 12px\">")
                .append("<table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD_BG)
                .append("\" style=\"width:600px;max-width:600px;background-color:").append(CARD_BG)
                .append(";border:1px solid ").append(cardBorder).append(";border-collapse:collapse\"><tbody>");

        appendHeader(out);
        if (stripLabel != null) {
            appendStrip(out, level);
        }

        out.append("<tr><td bgcolor=\"").append(CARD_BG).append("\" style=\"background-color:").append(CARD_BG)
                .append(";padding:28px\">")
                .append("<h1 style=\"margin:0;font-family:").append(SANS)
                .append(";font-size:21px;line-height:1.35;font-weight:600;color:").append(TEXT).append("\">")
                .append(escape(headline)).append("</h1>");

        boolean afterFlow = true; // an h1 or paragraph directly precedes the next block
        for (Block block : blocks) {
            afterFlow = appendBlock(out, block, level, afterFlow);
        }

        out.append("</td></tr>");
        appendFooter(out);

        out.append("</tbody></table></td></tr></tbody></table>\n</body>\n</html>");
        return out.toString();
    }

    private void appendHeader(StringBuilder out) {
        // The wordmark is text on a teal cell rather than an image, so it renders with images
        // blocked. The ❯_ is a static mark: nothing here is an input, so nothing blinks.
        out.append("<tr><td bgcolor=\"").append(CARD_BG).append("\" style=\"background-color:").append(CARD_BG)
                .append(";padding:20px 28px;border-bottom:1px solid ").append(HAIRLINE).append("\">")
                .append("<span style=\"font-family:").append(MONO)
                .append(";font-size:14px;font-weight:700;color:").append(ACCENT_INK)
                .append(";background-color:").append(ACCENT).append(";padding:3px 9px\">&#10095;_ wharf</span>")
                .append("</td></tr>");
    }

    private void appendStrip(StringBuilder out, MailSeverity level) {
        out.append("<tr><td bgcolor=\"").append(level.background())
                .append("\" style=\"background-color:").append(level.background())
                .append(";border-bottom:1px solid ").append(level.border())
                .append(";border-left:3px solid ").append(level.rule())
                .append(";padding:13px 28px;font-family:").append(MONO)
                .append(";font-size:12px;letter-spacing:.08em;color:").append(level.text()).append("\">")
                .append(escape(stripLabel).replace(" · ", " &nbsp;&middot;&nbsp; "))
                .append("</td></tr>");
    }

    /** Renders one block; returns whether the next block follows flowing text. */
    private boolean appendBlock(StringBuilder out, Block block, MailSeverity level, boolean afterFlow) {
        switch (block) {
            case Paragraph paragraph -> {
                // Directly after another paragraph the top margin supplies the gap; after a
                // table or button a spacer div already did, so the margin would double it.
                if (afterFlow) {
                    out.append("<p style=\"margin:12px 0 0;font-family:");
                } else {
                    spacer(out, 20);
                    out.append("<p style=\"margin:0;font-family:");
                }
                out.append(SANS).append(";font-size:15px;line-height:1.65;color:").append(MUTED).append("\">")
                        .append(inlineCode(paragraph.text(), paragraph.codeTerm()))
                        .append("</p>");
                return true;
            }
            case CodePanel panel -> {
                spacer(out, 20);
                out.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                        .append("style=\"border-collapse:collapse\"><tbody><tr>")
                        .append("<td align=\"center\" bgcolor=\"").append(PANEL_BG)
                        .append("\" style=\"background-color:").append(PANEL_BG)
                        .append(";border:1px solid ").append(BORDER).append(";padding:22px 16px\">")
                        .append("<div style=\"font-family:").append(MONO)
                        .append(";font-size:34px;line-height:1.2;font-weight:700;letter-spacing:.34em;color:")
                        .append(TEXT).append("\">").append(escape(panel.code())).append("</div>");
                if (panel.caption() != null) {
                    out.append("<div style=\"font-family:").append(MONO).append(";font-size:12px;color:")
                            .append(MUTED).append(";padding-top:12px\">").append(escape(panel.caption()))
                            .append("</div>");
                }
                out.append("</td></tr></tbody></table>");
                return false;
            }
            case Details details -> {
                spacer(out, 20);
                out.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"")
                        .append(PANEL_BG).append("\" style=\"background-color:").append(PANEL_BG)
                        .append(";border:1px solid ").append(BORDER).append(";border-collapse:collapse\"><tbody>");
                List<DetailRow> rows = details.rows();
                for (int i = 0; i < rows.size(); i++) {
                    boolean last = i == rows.size() - 1;
                    String rule = last ? "" : "border-bottom:1px solid " + HAIRLINE + ";";
                    out.append("<tr><td").append(i == 0 ? " width=\"120\"" : "")
                            .append(" style=\"padding:10px 16px;").append(rule).append("font-family:").append(MONO)
                            .append(";font-size:11px;letter-spacing:.08em;color:").append(MUTED).append("\">")
                            .append(escape(rows.get(i).label())).append("</td>")
                            .append("<td style=\"padding:10px 16px;").append(rule).append("font-family:").append(MONO)
                            .append(";font-size:13px;color:").append(TEXT).append("\">")
                            .append(escape(rows.get(i).value())).append("</td></tr>");
                }
                out.append("</tbody></table>");
                return false;
            }
            case Cta cta -> {
                spacer(out, 22);
                // Square button: a td with a background and padding, anchor as inline-block.
                // No rounded corners means no VML fallback is needed for Outlook.
                out.append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse\">")
                        .append("<tbody><tr><td align=\"center\" bgcolor=\"").append(ACCENT)
                        .append("\" style=\"background-color:").append(ACCENT).append(";padding:13px 26px\">")
                        .append("<a href=\"").append(escape(cta.url())).append("\" style=\"font-family:").append(MONO)
                        .append(";font-size:14px;font-weight:700;color:").append(ACCENT_INK)
                        .append(";text-decoration:none;display:inline-block\">").append(escape(cta.label()))
                        .append("</a></td></tr></tbody></table>");
                return false;
            }
            case Note note -> {
                spacer(out, 18);
                out.append("<div style=\"font-family:").append(SANS).append(";font-size:13px;line-height:1.65;color:")
                        .append(MUTED).append("\">").append(escape(note.text()));
                if (note.linkUrl() != null) {
                    out.append(" <a href=\"").append(escape(note.linkUrl())).append("\" style=\"color:").append(MUTED)
                            .append(";text-decoration:underline\">").append(escape(note.linkLabel())).append("</a>");
                }
                out.append("</div>");
                return false;
            }
            case Callout callout -> {
                spacer(out, 22);
                out.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"")
                        .append(level.background()).append("\" style=\"background-color:").append(level.background())
                        .append(";border:1px solid ").append(level.border())
                        .append(";border-left:3px solid ").append(level.rule())
                        .append(";border-collapse:collapse\"><tbody><tr><td style=\"padding:13px 16px;font-family:")
                        .append(SANS).append(";font-size:14px;line-height:1.6;color:").append(level.text())
                        .append("\">").append(escape(callout.text())).append("</td></tr></tbody></table>");
                return false;
            }
        }
    }

    private void appendFooter(StringBuilder out) {
        out.append("<tr><td bgcolor=\"").append(CARD_BG).append("\" style=\"background-color:").append(CARD_BG)
                .append(";border-top:1px solid ").append(HAIRLINE).append(";padding:18px 28px\">")
                .append("<div style=\"font-family:").append(SANS).append(";font-size:12px;line-height:1.6;color:")
                .append(DIM).append("\">").append(escape(footerWhy));
        if (notificationSettingsUrl != null) {
            out.append(" <a href=\"").append(escape(notificationSettingsUrl)).append("\" style=\"color:").append(MUTED)
                    .append(";text-decoration:underline\">Notification settings</a>");
        }
        out.append("</div>")
                .append("<div style=\"font-family:").append(MONO).append(";font-size:11px;color:").append(DIM)
                .append(";padding-top:8px\">").append(escape(sendingDomain)).append("</div>")
                .append("</td></tr>");
    }

    /** Vertical rhythm without margins, which several clients collapse or ignore. */
    private static void spacer(StringBuilder out, int px) {
        out.append("<div style=\"height:").append(px).append("px;line-height:").append(px)
                .append("px;font-size:").append(px).append("px\">&nbsp;</div>");
    }

    // ---------------------------------------------------------------- text

    private String renderText() {
        StringBuilder out = new StringBuilder(1024);
        out.append(stripLabel == null ? "WHARF" : "WHARF -- " + stripLabel.replace(" · ", ": ")).append("\n\n");
        out.append(wrap(headline)).append('\n');

        for (Block block : blocks) {
            out.append('\n');
            switch (block) {
                case Paragraph paragraph -> out.append(wrap(paragraph.text())).append('\n');
                case CodePanel panel -> {
                    out.append("  ").append(panel.code()).append('\n');
                    if (panel.caption() != null) {
                        out.append('\n').append(wrap(capitalize(panel.caption()) + '.')).append('\n');
                    }
                }
                case Details details -> {
                    int width = details.rows().stream().mapToInt(row -> row.label().length()).max().orElse(0) + 1;
                    for (DetailRow row : details.rows()) {
                        out.append("  ").append(pad(capitalize(row.label()) + ':', width + 1))
                                .append(row.value()).append('\n');
                    }
                }
                // The URL goes on its own line so a long link is never broken by wrapping,
                // which would make it unclickable in a plain-text reader.
                case Cta cta -> out.append(cta.label()).append(":\n").append(cta.url()).append('\n');
                case Note note -> {
                    out.append(wrap(note.text())).append('\n');
                    if (note.linkUrl() != null) {
                        out.append(note.linkLabel()).append(": ").append(note.linkUrl()).append('\n');
                    }
                }
                case Callout callout -> out.append(wrap(callout.text())).append('\n');
            }
        }

        out.append("\n--\n").append(wrap(footerWhy)).append('\n');
        if (notificationSettingsUrl != null) {
            out.append("Notification settings: ").append(notificationSettingsUrl).append('\n');
        }
        out.append(sendingDomain);
        return out.toString();
    }

    private static String wrap(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (lineLength > 0 && lineLength + 1 + word.length() > TEXT_WIDTH) {
                out.append('\n');
                lineLength = 0;
            } else if (lineLength > 0) {
                out.append(' ');
                lineLength++;
            }
            out.append(word);
            lineLength += word.length();
        }
        return out.toString();
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value + " " : value + " ".repeat(width - value.length());
    }

    /** Labels that are acronyms rather than words, and so keep their case in the text body. */
    private static final Set<String> ACRONYMS = Set.of("IP");

    /**
     * Detail labels and code captions are authored upper-case because that is how the HTML
     * renders them; the plain-text body wants sentence case instead.
     */
    private static String capitalize(String value) {
        if (ACRONYMS.contains(value)) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String inlineCode(String text, String codeTerm) {
        String escaped = escape(text);
        if (codeTerm == null) {
            return escaped;
        }
        String escapedTerm = escape(codeTerm);
        return escaped.replace(escapedTerm,
                "<span style=\"font-family:" + MONO + ";color:" + TEXT + "\">" + escapedTerm + "</span>");
    }

    /**
     * Escape every value that reaches the HTML body. Project names and device labels are
     * chosen by other users, so this is a genuine injection boundary and not a formality.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ---------------------------------------------------------------- blocks

    /** One label/value pair in the detail table. */
    public record DetailRow(String label, String value) {
    }

    private sealed interface Block {
    }

    private record Paragraph(String text, String codeTerm) implements Block {
    }

    private record CodePanel(String code, String caption) implements Block {
    }

    private record Details(List<DetailRow> rows) implements Block {
    }

    private record Cta(String label, String url) implements Block {
    }

    private record Note(String text, String linkLabel, String linkUrl) implements Block {
    }

    private record Callout(String text) implements Block {
    }
}
