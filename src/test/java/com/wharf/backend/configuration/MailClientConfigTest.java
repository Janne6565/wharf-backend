package com.wharf.backend.configuration;

import com.wharf.backend.client.MailPort;
import com.wharf.backend.client.MailServiceClient;
import com.wharf.backend.client.NoopMailClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MailClientConfigTest {

    private static final String BASE_URL = "https://mail-service.jannekeipert.de";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(MailClientConfig.class);

    @Test
    void mailPort_isNoop_whenApiKeyBlank() {
        runner.withBean(MailProperties.class, () -> new MailProperties(BASE_URL, ""))
                .run(context -> assertThat(context.getBean(MailPort.class))
                        .isInstanceOf(NoopMailClient.class));
    }

    @Test
    void mailPort_isRealClient_whenApiKeyConfigured() {
        runner.withBean(MailProperties.class, () -> new MailProperties(BASE_URL, "mk_key_secret"))
                .run(context -> assertThat(context.getBean(MailPort.class))
                        .isInstanceOf(MailServiceClient.class));
    }
}
