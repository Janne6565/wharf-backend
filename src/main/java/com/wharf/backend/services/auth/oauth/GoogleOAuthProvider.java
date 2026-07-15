package com.wharf.backend.services.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Google via OIDC. The code is exchanged at the token endpoint for an {@code id_token},
 * whose claims ({@code sub}, {@code email}, {@code email_verified}) carry the identity.
 *
 * <p>The id_token's claims are read directly from its payload segment <em>without</em>
 * cryptographic signature verification. That is safe here because the token is obtained
 * over a direct, server-to-server TLS channel to {@code oauth2.googleapis.com} in exchange
 * for our confidential client secret — the transport authenticates the issuer, so we trust
 * what the token endpoint just handed us on that channel.
 */
@Component
class GoogleOAuthProvider implements OAuthProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    GoogleOAuthProvider(@Qualifier("oauthRestClient") RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public OAuthUserIdentity authenticate(String code, String redirectUri,
                                          OAuthProperties.Credentials credentials) {
        String idToken = exchangeForIdToken(code, redirectUri, credentials);
        JsonNode claims = decodeIdTokenClaims(idToken);

        String subject = text(claims, "sub");
        String email = text(claims, "email");
        boolean emailVerified = claims.path("email_verified").asBoolean(false);
        if (subject == null || email == null) {
            log.error("Google id_token missing sub/email claim");
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR);
        }
        return new OAuthUserIdentity(subject, email, emailVerified);
    }

    private String exchangeForIdToken(String code, String redirectUri,
                                      OAuthProperties.Credentials credentials) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());

        Map<String, Object> response = postForm(OAuthProvider.GOOGLE.getTokenUri(), form);
        Object idToken = response == null ? null : response.get("id_token");
        if (idToken == null) {
            log.error("Google token exchange returned no id_token");
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR);
        }
        return String.valueOf(idToken);
    }

    private Map<String, Object> postForm(String uri, MultiValueMap<String, String> form) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return body;
        } catch (RuntimeException ex) {
            log.error("Google token exchange failed", ex);
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR, ex);
        }
    }

    private JsonNode decodeIdTokenClaims(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            log.error("Google id_token is not a well-formed JWT");
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR);
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException | java.io.IOException ex) {
            log.error("Failed to parse Google id_token claims", ex);
            throw new OAuthCallbackException(OAuthErrorCode.PROVIDER_ERROR, ex);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
