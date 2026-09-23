package com.bss.billing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The billing run's scale-out lesson: one aged account's pathological
 * usage rating hung a no-timeout HTTP call for fifteen minutes and the
 * whole run queued behind it. A machine call either answers in bounded
 * time or fails THAT account alone — the run walks on either way.
 */
@Configuration
public class HttpClientTimeouts {

    @Bean
    RestClientCustomizer restClientTimeouts(
            @Value("${bss.billing.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${bss.billing.http.read-timeout:60s}") Duration readTimeout) {
        return builder -> {
            // This customizer replaces the auto-configured factory, so it is the
            // one place billing's client timeouts live — spring.http.client.*
            // would never reach these clients. Connecting was still unbounded:
            // the read timeout above only starts once a connection exists, and a
            // host that never completes a handshake never reaches it.
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(connectTimeout).build());
            factory.setReadTimeout(readTimeout);
            builder.requestFactory(factory);
        };
    }
}
