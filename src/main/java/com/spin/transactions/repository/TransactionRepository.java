package com.spin.transactions.repository;

import com.spin.transactions.exception.ConcurrentTransactionUpdateException;
import com.spin.transactions.exception.TransactionNotFoundException;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Transaction}. Talks to Postgres via
 * {@code JdbcClient}; no ORM.
 *
 * <p>The three {@code markX} methods implement an <b>atomic state guard</b>: each
 * one is a conditional {@code UPDATE ... WHERE id = ? AND status = 'PENDING'}
 * and throws {@link ConcurrentTransactionUpdateException} if it affects zero
 * rows — that is, if someone already moved the row out of
 * {@link TransactionStatus#PENDING PENDING}. This is deliberately used <b>in
 * place of an optimistic-lock version column</b>: cheaper and equally atomic.
 */
public interface TransactionRepository {

    /**
     * Inserts a new transaction (expected in {@code PENDING}).
     *
     * <p><b>Idempotency contract:</b> the schema has a partial unique index on
     * {@code (account_id, idempotency_key) WHERE idempotency_key IS NOT NULL}.
     * If the insert fails with {@code DuplicateKeyException}:
     * <ul>
     *   <li>and the incoming transaction <b>has</b> an idempotency key, the
     *       method swallows the exception and returns the pre-existing row —
     *       resolving the idempotent race atomically without a check-then-act;</li>
     *   <li>and the incoming transaction has <b>no</b> idempotency key, the
     *       exception is rethrown as a genuine conflict.</li>
     * </ul>
     * The caller must inspect {@link Transaction#status()} on the returned row
     * to detect the idempotent-hit case (status will not be {@code PENDING}).
     */
    Transaction save(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    Optional<Transaction> findByIdempotencyKey(String accountId, String idempotencyKey);

    /**
     * Filter-and-paginate. The predicates are applied only when non-null in
     * {@link TransactionFilter}, which keeps the plan index-friendly.
     *
     * <p>{@code offset} is a {@code long} so a caller with an extreme
     * {@code page * limit} does not overflow to a negative int.
     */
    List<Transaction> findByFilters(TransactionFilter filter, int limit, long offset);

    /**
     * Transitions the row to {@link TransactionStatus#EXECUTED EXECUTED}.
     *
     * @throws ConcurrentTransactionUpdateException if the row is no longer in {@code PENDING}
     * @throws TransactionNotFoundException         if the id does not exist
     */
    Transaction markExecuted(UUID id, String providerTransactionId, BigDecimal balanceAfter, Instant now);

    /**
     * Transitions the row to {@link TransactionStatus#REJECTED REJECTED} — the
     * provider explicitly declined the transaction (known outcome).
     *
     * @throws ConcurrentTransactionUpdateException if the row is no longer in {@code PENDING}
     * @throws TransactionNotFoundException         if the id does not exist
     */
    Transaction markRejected(UUID id, String failureCode, String failureMessage, Instant now);

    /**
     * Transitions the row to {@link TransactionStatus#FAILED FAILED} — either
     * the provider was unavailable (safe to reattempt) or the outcome is
     * unknown (read timeout; charge may or may not have gone through — needs
     * manual reconciliation, never retried automatically).
     *
     * @throws ConcurrentTransactionUpdateException if the row is no longer in {@code PENDING}
     * @throws TransactionNotFoundException         if the id does not exist
     */
    Transaction markFailed(UUID id, String failureMessage, Instant now);
}
