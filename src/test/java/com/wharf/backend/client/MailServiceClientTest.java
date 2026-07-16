package com.wharf.backend.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MailServiceClientTest {

    private static final String BASE_URL = "https://mail-service.jannekeipert.de";
    private static final String API_KEY = "mk_11111111-1111-1111-1111-111111111111_secret";

    private MockRestServiceServer server;
    private MailServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MailServiceClient(builder.build(), BASE_URL, API_KEY);
    }

    @Test
    void send_postsBearerAuthorizedJsonBody() {
        server.expect(requestTo(BASE_URL + "/api/v1/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.recipient").value("invitee@acme.io"))
                .andExpect(jsonPath("$.subject").value("Hello"))
                .andExpect(jsonPath("$.body").value("Body text"))
                .andExpect(jsonPath("$.enableHtml").value(false))
                .andRespond(withSuccess());

        client.send("invitee@acme.io", "Hello", "Body text", false);

        server.verify();
    }

    @Test
    void send_serverError_isSwallowedNotThrown() {
        server.expect(requestTo(BASE_URL + "/api/v1/send"))
                .andRespond(withServerError());

        assertThatCode(() -> client.send("invitee@acme.io", "Hello", "Body text", false))
                .doesNotThrowAnyException();

        server.verify();
    }
}
