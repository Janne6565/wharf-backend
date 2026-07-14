package com.wharf.backend.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI wharfOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wharf Backend API")
                        .version("v1")
                        .description("""
                                Zero-knowledge sync backend for Wharf. The server only ever stores
                                ciphertext vault blobs and bcrypt hashes of client-derived credential
                                keys. It never sees passwords or plaintext vault contents."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Identity (access) token issued by /api/v1/auth.")));
    }
}
