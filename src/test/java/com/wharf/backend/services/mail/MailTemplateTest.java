package com.wharf.backend.services.mail;

import com.wharf.backend.services.mail.MailTemplate.DetailRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The renderer is the piece most likely to fail silently: a broken template still sends, and
 * nobody sees the result until it is already in somebody's inbox. These tests pin the
 * constraints that make the output survive real mail clients, and the escaping that makes it
 * safe to put another user's text in front of a third party.
 */
class MailTemplateTest {

    private static MailTemplate minimal() {
        return MailTemplate.subject("Subject")
                .headline("Headline")
                .paragraph("Body copy.")
                .footer("You got this because of a thing.");
    }

    @Test
    void build_producesBothRepresentations() {
        MailContent content = minimal().build();

        assertThat(content.subject()).isEqualTo("Subject");
        assertThat(content.html()).contains("Headline").contains("Body copy.");
        assertThat(content.text()).contains("Headline").contains("Body copy.");
    }

    /**
     * Everything a mail client might refuse to fetch, or cannot render at all. A page that
     * needs any of these is a page that arrives broken for a large share of readers.
     */
    @Test
    void html_referencesNothingExternalAndUsesNoUnsupportedFeatures() {
        String html = minimal()
                .strip(MailSeverity.DANGER, "SECURITY ALERT · SOMETHING")
                .details(List.of(new DetailRow("TIME", "1 Jan 2026, 00:00 UTC")))
                .cta("Do the thing", "https://wharf.example/account")
                .callout("If this wasn't you, act.")
                .build().html();

        assertThat(html)
                .doesNotContain("<script")
                .doesNotContain("<svg")
                .doesNotContain("<link")
                .doesNotContain("<img")
                .doesNotContain("background-image")
                .doesNotContain("gradient")
                .doesNotContain("display:flex")
                .doesNotContain("display:grid")
                .doesNotContain("@media")
                .doesNotContain("border-radius");
    }

    /** Outlook ignores CSS backgrounds on cells, so every coloured cell also needs the attribute. */
    @Test
    void html_declaresColourAsBothAttributeAndStyle() {
        String html = minimal().build().html();

        assertThat(html).contains("bgcolor=\"#0c1219\"").contains("background-color:#0c1219");
    }

    @Test
    void html_isFixedAt600Pixels() {
        assertThat(minimal().build().html()).contains("width:600px;max-width:600px");
    }

    /**
     * Severity is carried by colour and words alone. If it were carried by an icon it would
     * disappear in Gmail (which strips SVG) and in Outlook (whose Word engine cannot draw it).
     */
    @Test
    void strip_escalatesTheCardBorderOnlyForDanger() {
        String warning = minimal().strip(MailSeverity.WARNING, "SECURITY NOTICE · X").build().html();
        String danger = minimal().strip(MailSeverity.DANGER, "SECURITY ALERT · X").build().html();

        assertThat(warning).contains("border:1px solid #233140");
        assertThat(danger).contains("border:1px solid #5a2a28");
    }

    @Test
    void strip_headsThePlainTextWithItsLabel() {
        assertThat(minimal().strip(MailSeverity.DANGER, "SECURITY ALERT · RECOVERY CODE USED").build().text())
                .startsWith("WHARF -- SECURITY ALERT: RECOVERY CODE USED");
        assertThat(minimal().build().text()).startsWith("WHARF\n");
    }

    /**
     * Project names, device labels and email addresses are chosen by other people. A
     * notification is the one place attacker-supplied text reaches a third party's inbox.
     */
    @Test
    void html_escapesEveryInterpolatedValue() {
        String html = MailTemplate.subject("s")
                .headline("<b>bold</b>")
                .paragraph("a & b")
                .details(List.of(new DetailRow("NAME", "\"><script>alert(1)</script>")))
                .cta("Go", "https://wharf.example/?a=1&b=2")
                .footer("why")
                .build().html();

        assertThat(html)
                .doesNotContain("<b>bold</b>")
                .doesNotContain("<script>")
                .contains("&lt;b&gt;bold&lt;/b&gt;")
                .contains("a &amp; b")
                .contains("https://wharf.example/?a=1&amp;b=2");
    }

    /** A row with nothing to show is dropped rather than rendered blank — see location. */
    @Test
    void details_dropsRowsWithNoValue() {
        String html = minimal()
                .details(Arrays.asList(
                        new DetailRow("TIME", "1 Jan 2026, 00:00 UTC"),
                        new DetailRow("IP", null),
                        new DetailRow("CLIENT", "  ")))
                .build().html();

        assertThat(html).contains("TIME").doesNotContain("IP").doesNotContain("CLIENT");
    }

    @Test
    void details_omittedEntirelyWhenEveryRowIsEmpty() {
        assertThat(minimal().details(Arrays.asList(new DetailRow("IP", null))).build().html())
                .doesNotContain("padding:10px 16px");
    }

    @Test
    void text_alignsDetailRowsAndTitleCasesTheLabels() {
        String text = minimal()
                .details(List.of(
                        new DetailRow("TIME", "1 Jan 2026, 00:00 UTC"),
                        new DetailRow("CLIENT", "Firefox 141 on macOS")))
                .build().text();

        assertThat(text)
                .contains("  Time:   1 Jan 2026, 00:00 UTC")
                .contains("  Client: Firefox 141 on macOS");
    }

    /** A wrapped URL is an unclickable URL in a plain-text reader. */
    @Test
    void text_putsTheCtaUrlOnItsOwnUnwrappedLine() {
        String text = minimal()
                .cta("Review your account", "https://wharf.example/account/with/a/very/long/path/indeed")
                .build().text();

        assertThat(text).contains("Review your account:\nhttps://wharf.example/account/with/a/very/long/path/indeed");
    }

    @Test
    void text_wrapsProseButNeverExceedsTheColumnLimit() {
        String text = MailTemplate.subject("s")
                .headline("Headline")
                .paragraph("word ".repeat(60).trim())
                .footer("why")
                .build().text();

        assertThat(text.lines()).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(68));
    }

    @Test
    void footer_alwaysEndsWithTheSendingDomain() {
        assertThat(minimal().sendingDomain("wharf.example").build().text()).endsWith("wharf.example");
    }

    /** Security notices carry no unsubscribe; collaboration mail carries exactly one link. */
    @Test
    void notificationSettingsLink_isOptOut() {
        assertThat(minimal().build().html()).doesNotContain("Notification settings");
        assertThat(minimal().notificationSettings("https://wharf.example/account").build().html())
                .contains("Notification settings");
    }

    /**
     * A paragraph following a spacer must not add its own top margin, or the gap doubles.
     * Trivial-looking, but it is the difference between the design's rhythm and a mail that
     * looks subtly wrong in every client at once.
     */
    @Test
    void paragraphAfterANonFlowBlock_dropsItsTopMargin() {
        String html = MailTemplate.subject("s")
                .headline("Headline")
                .paragraph("First.")
                .codePanel("123456", "expires in 10 minutes")
                .paragraph("After the panel.")
                .footer("why")
                .build().html();

        assertThat(html).contains("<p style=\"margin:12px 0 0;").contains("<p style=\"margin:0;");
    }

    @Test
    void codePanel_rendersTheCodeAsSelectableText() {
        String html = minimal().codePanel("042042", "expires in 15 minutes").build().html();

        assertThat(html).contains(">042042<");
    }

    @Test
    void inlineCode_setsOnlyTheNamedTermInMonospace() {
        String html = MailTemplate.subject("s")
                .headline("Headline")
                .paragraph("Run wharf sync in your terminal to pull them.", "wharf sync")
                .footer("why")
                .build().html();

        assertThat(html).contains("<span style=\"font-family:ui-monospace,'SFMono-Regular',Menlo,monospace;"
                + "color:#e8edf2\">wharf sync</span>");
    }
}
