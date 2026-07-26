package com.wharf.backend.security.ratelimit;

import com.wharf.backend.configuration.RateLimitProperties;
import com.wharf.backend.security.ProblemDetailWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket a path lands in is decided by ordering, so these tests pin the ordering
 * itself: the verification endpoints must be recognised before the generic auth prefix
 * swallows them into its far looser bucket.
 */
class RateLimitFilterTest {

    /** Deliberately lopsided: the verify bucket is tiny, the generic auth bucket is huge. */
    private static final int VERIFY_CAPACITY = 2;
    private static final int AUTH_CAPACITY = 1_000;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties(
                true, false,
                AUTH_CAPACITY, Duration.ofMinutes(1),
                50, Duration.ofMinutes(1),
                VERIFY_CAPACITY, Duration.ofMinutes(1),
                50, Duration.ofHours(1));
        filter = new RateLimitFilter(properties, new ProblemDetailWriter(new ObjectMapper()));
    }

    private int statusAfterPost(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    @Test
    void verifyEmail_isMetredByTheTightVerifyBucketNotTheGenericAuthOne() throws Exception {
        assertThat(statusAfterPost("/api/v1/auth/verify-email")).isEqualTo(HttpStatus.OK.value());
        assertThat(statusAfterPost("/api/v1/auth/verify-email")).isEqualTo(HttpStatus.OK.value());
        // Third request within the window: only the VERIFY capacity of 2 can explain this.
        assertThat(statusAfterPost("/api/v1/auth/verify-email"))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void resendVerification_sharesTheSameVerifyBucket() throws Exception {
        assertThat(statusAfterPost("/api/v1/auth/verify-email")).isEqualTo(HttpStatus.OK.value());
        assertThat(statusAfterPost("/api/v1/auth/resend-verification")).isEqualTo(HttpStatus.OK.value());
        assertThat(statusAfterPost("/api/v1/auth/resend-verification"))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void otherAuthPaths_keepTheirOwnRoomyBucket() throws Exception {
        // Exhausting the verify bucket must not spill over into ordinary logins.
        statusAfterPost("/api/v1/auth/verify-email");
        statusAfterPost("/api/v1/auth/verify-email");
        statusAfterPost("/api/v1/auth/verify-email");

        assertThat(statusAfterPost("/api/v1/auth/login")).isEqualTo(HttpStatus.OK.value());
    }
}
