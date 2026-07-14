package com.wharf.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WharfBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(WharfBackendApplication.class, args);
    }
}
