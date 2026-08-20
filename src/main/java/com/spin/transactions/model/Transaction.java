package com.spin.transactions.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Transaction(
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
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt
) {

    public Transaction {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
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

    public static Transaction pending(TransactionCommand command, Instant now) {
        Objects.requireNonNull(command, "command is required");
        Objects.requireNonNull(now, "now is required");
        return new Transaction(
                UUID.randomUUID(),
                command.accountId(),
                command.type(),
                command.amount(),
                command.currency(),
                command.description(),
                TransactionStatus.PENDING,
                null,
                null,
                null,
                null,
                command.idempotencyKey(),
                now,
                now
        );
    }

    public Transaction markExecuted(String providerTransactionId, BigDecimal balanceAfter, Instant now) {
        Objects.requireNonNull(providerTransactionId, "providerTransactionId is required");
        Objects.requireNonNull(balanceAfter, "balanceAfter is required");
        Objects.requireNonNull(now, "now is required");
        return new Transaction(
                id,
                accountId,
                type,
                amount,
                currency,
                description,
                TransactionStatus.EXECUTED,
                providerTransactionId,
                balanceAfter,
                failureCode,
                failureMessage,
                idempotencyKey,
                createdAt,
                now
        );
    }

    public Transaction markRejected(String failureCode, String failureMessage, Instant now) {
        Objects.requireNonNull(failureCode, "failureCode is required");
        Objects.requireNonNull(failureMessage, "failureMessage is required");
        Objects.requireNonNull(now, "now is required");
        return new Transaction(
                id,
                accountId,
                type,
                amount,
                currency,
                description,
                TransactionStatus.REJECTED,
                providerTransactionId,
                balanceAfter,
                failureCode,
                failureMessage,
                idempotencyKey,
                createdAt,
                now
        );
    }

    public Transaction markFailed(String failureMessage, Instant now) {
        Objects.requireNonNull(failureMessage, "failureMessage is required");
        Objects.requireNonNull(now, "now is required");
        return new Transaction(
                id,
                accountId,
                type,
                amount,
                currency,
                description,
                TransactionStatus.FAILED,
                providerTransactionId,
                balanceAfter,
                null,
                failureMessage,
                idempotencyKey,
                createdAt,
                now
        );
    }
}
