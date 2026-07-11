package com.bss.gateway.tenant;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Netty's default DNS resolver caches container IPs — and Docker reassigns
 * IPs across recreates, so a cached name can silently point at a DIFFERENT
 * service (the "wrong nginx answers /app" incident, three times). The JVM
 * resolver honors Docker's embedded DNS on every lookup; the per-request
 * cost is negligible at this scale.
 */
@Configuration
public class DnsConfig {

    @Bean
    HttpClientCustomizer jvmDnsResolver() {
        return httpClient -> httpClient.resolver(DefaultAddressResolverGroup.INSTANCE);
    }
}
