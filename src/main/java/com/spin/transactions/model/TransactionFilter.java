package com.spin.transactions.model;

public record TransactionFilter(
        String accountId,
        TransactionStatus status,
        TransactionType type
) {
}
