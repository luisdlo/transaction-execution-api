package com.spin.transactions.model;

import java.math.BigDecimal;
import java.util.Objects;

public record TransactionCommand(
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String description,
        String idempotencyKey
) {
    public TransactionCommand {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
    }
}
