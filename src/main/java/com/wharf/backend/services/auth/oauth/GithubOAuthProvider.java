package com.wharf.backend.services.auth.oauth;

import com.wharf.backend.configuration.OAuthProperties;
import com.wharf.backend.model.core.OAuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * GitHub OAuth2. The code is exchanged for an access token, then {@code /user} yields the
 * stable numeric {@code id} (subject) and {@code /user/emails} the primary, verified email
 * — GitHub's {@code /user} email can be null or unverified, so the emails endpoint is the
 * authoritative source. Scope {@code user:email} is required to read it.
 */
@Component
class GithubOAuthProvider implements OAuthProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GithubOAuthProvider.class);

    private static final String USER_URI = "https://api.github.com/user";
    private static final String USER_EMAILS_URI = "https://api.github.com/user/emails";

    private final RestClient restClient;

    GithubOAuthProvider(@Qualifier("oauthRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GITHUB;
    }

    @Override
    public OAuthUserIdentity authenticate(String code, String redirectUri,
                                          OAuthProperties.Credentials credentials) {
        String accessToken = exchangeForAccessToken(code, redirectUri, credentials);
        Map<String, Object> user = getJson(USER_URI, accessToken, Map.class);
        Object id = user == null ? null : user.get("id");
        if (id == null) {
            log.error("GitHub /user returned no id");
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR);
        }

        VerifiedEmail email = resolvePrimaryVerifiedEmail(accessToken);
        return new OAuthUserIdentity(String.valueOf(id), email.address(), email.verified());
    }

    private String exchangeForAccessToken(String code, String redirectUri,
                                          OAuthProperties.Credentials credentials) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());

        Map<String, Object> response;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri(OAuthProvider.GITHUB.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    // GitHub returns form-encoded by default; ask for JSON explicitly.
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            response = body;
        } catch (RuntimeException ex) {
            log.error("GitHub token exchange failed", ex);
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR, ex);
        }

        Object accessToken = response == null ? null : response.get("access_token");
        if (accessToken == null) {
            log.error("GitHub token exchange returned no access_token");
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR);
        }
        return String.valueOf(accessToken);
    }

    /**
     * Prefer the primary verified email; fall back to any verified email. If none is
     * verified, return the primary marked unverified so the service rejects it uniformly.
     */
    private VerifiedEmail resolvePrimaryVerifiedEmail(String accessToken) {
        List<Map<String, Object>> emails = getJson(USER_EMAILS_URI, accessToken, List.class);
        if (emails == null || emails.isEmpty()) {
            log.error("GitHub /user/emails returned no addresses");
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR);
        }

        Map<String, Object> primaryVerified = firstMatching(emails,
                e -> isTrue(e, "primary") && isTrue(e, "verified"));
        if (primaryVerified != null) {
            return new VerifiedEmail(String.valueOf(primaryVerified.get("email")), true);
        }

        Map<String, Object> anyVerified = firstMatching(emails, e -> isTrue(e, "verified"));
        if (anyVerified != null) {
            return new VerifiedEmail(String.valueOf(anyVerified.get("email")), true);
        }

        Map<String, Object> primary = firstMatching(emails, e -> isTrue(e, "primary"));
        Map<String, Object> fallback = primary != null ? primary : emails.get(0);
        return new VerifiedEmail(String.valueOf(fallback.get("email")), false);
    }

    private <T> T getJson(String uri, String accessToken, Class<T> type) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(type);
        } catch (RuntimeException ex) {
            log.error("GitHub API call to {} failed", uri, ex);
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR, ex);
        }
    }

    private Map<String, Object> firstMatching(List<Map<String, Object>> emails,
                                              java.util.function.Predicate<Map<String, Object>> predicate) {
        return emails.stream().filter(predicate).findFirst().orElse(null);
    }

    private boolean isTrue(Map<String, Object> entry, String key) {
        return Boolean.TRUE.equals(entry.get(key));
    }

    private record VerifiedEmail(String address, boolean verified) {
    }
}
