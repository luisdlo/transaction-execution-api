package com.spin.transactions.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Provider-facing configuration.
 *
 * The {@code retry.*} keys are consumed directly by {@code @Retryable} placeholders
 * on {@code HttpProviderClient} — they intentionally do not appear here as fields,
 * so there is no chance of them being read twice from different code paths.
 */
@ConfigurationProperties(prefix = "provider")
public record ProviderProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
