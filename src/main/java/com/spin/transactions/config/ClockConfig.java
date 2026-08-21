package com.spin.transactions.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The single source of "now" for the application.
 *
 * Every service that needs a timestamp injects {@link Clock} and calls
 * {@code Instant.now(clock)}. Tests replace this bean with {@code Clock.fixed(...)}
 * for deterministic assertions — {@code Instant.now()} without an argument is banned.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
