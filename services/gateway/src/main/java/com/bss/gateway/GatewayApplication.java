package com.bss.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single entry point for the BSS. Routes TMF API paths to the owning service
 * (see application.yml) and forwards Authorization headers untouched — the
 * services remain the authorization enforcement point, so the gateway adds a
 * front door without duplicating the security model.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
