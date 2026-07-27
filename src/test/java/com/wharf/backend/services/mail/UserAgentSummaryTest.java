package com.wharf.backend.services.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentSummaryTest {

    @Test
    void describe_namesBrowserAndOperatingSystem() {
        assertThat(UserAgentSummary.describe(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:141.0) Gecko/20100101 Firefox/141.0"))
                .isEqualTo("Firefox 141 on macOS");
    }

    /** Chrome and Edge both claim to be Safari, and Edge also claims to be Chrome. */
    @Test
    void describe_prefersTheMostSpecificBrowserClaim() {
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"))
                .isEqualTo("Edge 126 on Windows");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"))
                .isEqualTo("Chrome 126 on Windows");
    }

    /** A first-party client names itself precisely, so it wins over any browser token. */
    @Test
    void describe_prefersAWharfClientOverABrowserToken() {
        assertThat(UserAgentSummary.describe("wharf-tui/0.9.4 (linux; amd64)"))
                .isEqualTo("wharf-tui 0.9.4 on Linux");
        assertThat(UserAgentSummary.describe(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) Safari/605.1 wharf-ios/1.2.0"))
                .isEqualTo("wharf-ios 1.2.0 on iOS");
    }

    /** iPadOS in desktop mode still says "Mac OS X", so the iOS families are tested first. */
    @Test
    void describe_readsIpadAsIosNotMacOs() {
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 Version/18.0 Safari/604.1"))
                .isEqualTo("Safari 18 on iOS");
    }

    @Test
    void describe_fallsBackToTheRawHeaderWhenUnrecognised() {
        assertThat(UserAgentSummary.describe("curl/8.7.1")).isEqualTo("curl/8.7.1");
    }

    /** Headers are attacker-controlled, and this string is rendered into somebody's inbox. */
    @Test
    void describe_stripsControlCharactersAndCapsLength() {
        assertThat(UserAgentSummary.describe("evil\r\nBcc: victim@example.com")).doesNotContain("\n");
        assertThat(UserAgentSummary.describe("x".repeat(500))).hasSizeLessThanOrEqualTo(81);
    }

    @Test
    void describe_returnsNullWhenThereIsNothingToShow() {
        assertThat(UserAgentSummary.describe(null)).isNull();
        assertThat(UserAgentSummary.describe("   ")).isNull();
    }
}
