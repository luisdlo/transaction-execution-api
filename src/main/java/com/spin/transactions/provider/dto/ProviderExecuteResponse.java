package com.spin.transactions.provider.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Success-path body from the provider.
 *
 * {@code status} is a raw String on purpose. Modelling it as an enum would make
 * deserialization throw when the provider (which we do not control) starts sending
 * a value we did not anticipate. Keeping it String lets us dispatch defensively:
 * anything other than "APPROVED" is treated as a rejection.
 */
public record ProviderExecuteResponse(
        String transactionId,
        String status,
        BigDecimal balance,
        Instant executedAt
) {
}
