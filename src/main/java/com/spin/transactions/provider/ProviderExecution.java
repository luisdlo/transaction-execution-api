package com.spin.transactions.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of a successful call to the external provider.
 *
 * Lives in {@code provider} rather than {@code model} because it is the output
 * of the adapter, not a persisted domain concept. The service maps this into the
 * persisted {@code Transaction} by calling {@code markExecuted(...)}.
 */
public record ProviderExecution(
        String providerTransactionId,
        BigDecimal balanceAfter,
        Instant executedAt
) {
    public ProviderExecution {
        Objects.requireNonNull(providerTransactionId, "providerTransactionId is required");
        Objects.requireNonNull(balanceAfter, "balanceAfter is required");
        Objects.requireNonNull(executedAt, "executedAt is required");
        if (providerTransactionId.isBlank()) {
            throw new IllegalArgumentException("providerTransactionId must not be blank");
        }
    }
}
