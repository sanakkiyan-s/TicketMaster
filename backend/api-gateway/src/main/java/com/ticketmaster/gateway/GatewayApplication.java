package com.ticketmaster.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The single public entry point (ADR-014, ADR-034). Everything a browser can
 * reach comes through here; no backend service is exposed directly.
 *
 * @EnableScheduling is load-bearing rather than incidental: the JWKS cache is
 * refreshed by a background timer, and ADR-012 forbids refreshing it lazily on
 * the request path.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
