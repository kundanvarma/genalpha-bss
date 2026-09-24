package com.bss.basemigration.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * The engine's one source of "now". Injectable so tests advance a fake
 * clock across the notice window instead of sleeping through it — the
 * notice gate is law-shaped and must be provable without waiting 30 days.
 *
 * IT TICKS IN MILLISECONDS, AND THAT IS THE POINT. `Clock.systemUTC()` has
 * NANOSECOND resolution on Linux and microsecond on macOS. A timestamp column
 * holds microseconds and ROUNDS, so a row written at a nanosecond-precision
 * "now" can be stored a few hundred nanoseconds IN THE FUTURE relative to the
 * clock that wrote it — and a `scheduledFor <= now` due-check then skips work
 * that is, by every human measure, due. It self-heals on the next tick, so in
 * production it costs one cycle; in a test that ticks once it is a hard,
 * platform-dependent failure, which is exactly how it was found (green on
 * macOS, red on every Linux runner).
 *
 * An application's notion of "now" should never be finer than what it can
 * persist. A millisecond tick is coarser than the column, so a value written
 * at `now` stores exactly and compares equal.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock migrationClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }
}
