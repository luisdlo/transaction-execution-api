package com.spin.transactions.response;

import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionStatus;
import com.spin.transactions.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String description,
        TransactionStatus status,
        String providerTransactionId,
        BigDecimal balanceAfter,
        String failureCode,
        String failureMessage,
        Instant createdAt
) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.accountId(),
                transaction.type(),
                transaction.amount(),
                transaction.currency(),
                transaction.description(),
                transaction.status(),
                transaction.providerTransactionId(),
                transaction.balanceAfter(),
                transaction.failureCode(),
                transaction.failureMessage(),
                transaction.createdAt()
        );
    }
}
