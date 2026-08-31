package com.spin.transactions.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted transaction. Immutable record; each state transition returns a new
 * instance rather than mutating the existing one.
 *
 * <h2>Life cycle</h2>
 *
 * <pre>
 *              rules OK + insert            markExecuted(...)
 *   (client) ─────────────────▶ PENDING ────────────────────▶ EXECUTED
 *                                  │
 *                                  │  markRejected(code, msg) ─▶ REJECTED
 *                                  │
 *                                  └─ markFailed(msg) ─────────▶ FAILED
 * </pre>
 *
 * <p>The three terminal states carry different semantics:
 * <ul>
 *   <li>{@link TransactionStatus#EXECUTED EXECUTED} — the provider approved
 *       and returned {@code providerTransactionId} + {@code balanceAfter}.</li>
 *   <li>{@link TransactionStatus#REJECTED REJECTED} — the provider explicitly
 *       said no (insufficient funds, blocked account, …). {@code failureCode}
 *       and {@code failureMessage} are populated; provider-side no charge.</li>
 *   <li>{@link TransactionStatus#FAILED FAILED} — the outcome is either
 *       "provider unavailable" (safe to reattempt) or "unknown state"
 *       (read timeout — the charge may or may not have happened; needs
 *       reconciliation). Only {@code failureMessage} is set.</li>
 * </ul>
 *
 * <h2>Invariants</h2>
 * The compact constructor enforces non-null / non-blank fields and
 * {@code amount &gt; 0}. The transition methods enforce the required extra
 * fields per target state.
 *
 * <h2>Money</h2>
 * All monetary amounts are {@link BigDecimal} (never {@code double}). Compare
 * with {@link BigDecimal#compareTo compareTo}, not {@code equals}.
 */
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
