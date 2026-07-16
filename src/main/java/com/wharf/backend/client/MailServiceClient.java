package com.wharf.backend.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends mail through the external Mail Manager service
 * ({@code POST {baseUrl}/api/v1/send}) using a scoped API key
 * ({@code mk_<uuid>_<secret>}, SEND scope) carried as a bearer token.
 *
 * <p>Best-effort by contract: any non-2xx response or transport error is logged at
 * {@code WARN} with the recipient and (where available) the HTTP status, and then
 * swallowed. The caller's operation must never fail because a notification could not be
 * delivered.
 */
public class MailServiceClient implements MailPort {

    private static final Logger log = LoggerFactory.getLogger(MailServiceClient.class);

    private static final String SEND_PATH = "/api/v1/send";

    private final RestClient restClient;
    private final String sendUri;
    private final String apiKey;

    public MailServiceClient(RestClient restClient, String baseUrl, String apiKey) {
        this.restClient = restClient;
        this.sendUri = stripTrailingSlash(baseUrl) + SEND_PATH;
        this.apiKey = apiKey;
    }

    @Override
    public void send(String recipient, String subject, String body, boolean html) {
        try {
            restClient.post()
                    .uri(sendUri)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SendRequest(recipient, subject, body, html))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            log.warn("Mail send to {} failed with status {}", recipient, ex.getStatusCode());
        } catch (RuntimeException ex) {
            log.warn("Mail send to {} failed", recipient, ex);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** JSON payload the Mail Manager {@code /api/v1/send} endpoint expects. */
    record SendRequest(String recipient, String subject, String body, boolean enableHtml) {
    }
}
