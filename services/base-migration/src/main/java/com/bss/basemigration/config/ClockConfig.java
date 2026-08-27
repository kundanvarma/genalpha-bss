package com.bss.basemigration.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The engine's one source of "now". Injectable so tests advance a fake
 * clock across the notice window instead of sleeping through it — the
 * notice gate is law-shaped and must be provable without waiting 30 days.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock migrationClock() {
        return Clock.systemUTC();
    }
}
