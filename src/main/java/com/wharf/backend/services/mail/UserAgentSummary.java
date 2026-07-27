package com.wharf.backend.services.mail;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a {@code User-Agent} header into the short phrase a security notice shows, e.g.
 * "Firefox 141 on macOS" or "wharf-tui 0.9.4 on Linux".
 *
 * <p>This is intentionally a handful of patterns rather than a user-agent parsing library.
 * The string is only ever shown to a human deciding "was that me?", so approximate is fine
 * and a wrong-but-plausible answer from an over-eager parser would be worse than a vague
 * one. Order matters: Chrome and Edge both claim to be Safari, Edge claims to be Chrome, so
 * the most specific claim is tested first.
 */
final class UserAgentSummary {

    /** Unrecognised agents fall back to the raw header, capped so a hostile one cannot flood the mail. */
    private static final int RAW_LIMIT = 80;

    private static final Pattern WHARF_CLIENT = Pattern.compile("(wharf-[a-z]+)/([0-9][\\w.+-]*)");

    private record Browser(String name, Pattern version) {
    }

    private static final Browser[] BROWSERS = {
        new Browser("Edge", Pattern.compile("Edg(?:e|A|iOS)?/(\\d+)")),
        new Browser("Opera", Pattern.compile("OPR/(\\d+)")),
        new Browser("Firefox", Pattern.compile("(?:Firefox|FxiOS)/(\\d+)")),
        new Browser("Chrome", Pattern.compile("(?:Chrome|CriOS)/(\\d+)")),
        new Browser("Safari", Pattern.compile("Version/(\\d+)[\\d.]* Safari")),
    };

    private UserAgentSummary() {
    }

    /** Returns {@code null} when there is nothing worth showing, which drops the CLIENT row. */
    static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String cleaned = sanitize(userAgent);
        String os = operatingSystem(cleaned);

        // A first-party client names itself precisely; trust it over any browser token it
        // may also carry (the mobile app's WebView adds one).
        Matcher wharf = WHARF_CLIENT.matcher(cleaned);
        if (wharf.find()) {
            return withOs(wharf.group(1) + " " + wharf.group(2), os);
        }

        for (Browser browser : BROWSERS) {
            Matcher matcher = browser.version().matcher(cleaned);
            if (matcher.find()) {
                return withOs(browser.name() + " " + matcher.group(1), os);
            }
        }
        return cleaned.length() > RAW_LIMIT ? cleaned.substring(0, RAW_LIMIT) + "…" : cleaned;
    }

    private static String withOs(String client, String os) {
        return os == null ? client : client + " on " + os;
    }

    private static String operatingSystem(String agent) {
        String lower = agent.toLowerCase(Locale.ENGLISH);
        // iPadOS still says "Mac OS X" on desktop-mode Safari, so the iOS families are
        // checked before macOS rather than after.
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) {
            return "iOS";
        }
        if (lower.contains("android")) {
            return "Android";
        }
        if (lower.contains("mac os") || lower.contains("macos") || lower.contains("darwin")) {
            return "macOS";
        }
        if (lower.contains("windows")) {
            return "Windows";
        }
        if (lower.contains("linux") || lower.contains("x11")) {
            return "Linux";
        }
        return null;
    }

    /** Headers are attacker-controlled: drop control characters before they reach a mail body. */
    private static String sanitize(String value) {
        return value.replaceAll("[\\p{Cntrl}]", " ").trim().replaceAll("\\s{2,}", " ");
    }
}
