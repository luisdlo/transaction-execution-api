package com.spin.transactions.dto.response;

import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionStatus;
import com.spin.transactions.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response shape returned by {@code POST /transactions} (single) and
 * {@code GET /transactions} (as items of the paged list).
 *
 * <p>The record is separate from the persisted {@link Transaction} on purpose:
 * the model carries fields the API does <b>not</b> expose ({@code idempotencyKey},
 * {@code updatedAt}). If those ever need to be exposed, it is a conscious
 * change here — not an accident of serialising the persisted entity.
 *
 * <p>Field semantics per {@link #status()}:
 * <ul>
 *   <li>{@code EXECUTED} — {@code providerTransactionId} and {@code balanceAfter}
 *       are set; {@code failureCode} / {@code failureMessage} are {@code null}.</li>
 *   <li>{@code REJECTED} — {@code failureCode} and {@code failureMessage} are set
 *       (e.g. {@code INSUFFICIENT_FUNDS}); the provider fields are {@code null}.</li>
 *   <li>{@code FAILED} — only {@code failureMessage} is set; both provider fields
 *       are {@code null}. The client should treat the outcome as unknown until
 *       manual reconciliation.</li>
 * </ul>
 */
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
