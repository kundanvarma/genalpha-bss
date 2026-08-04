package com.bss.billing.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

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
    RestClientCustomizer restClientTimeouts() {
        return builder -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(60));
            builder.requestFactory(factory);
        };
    }
}
