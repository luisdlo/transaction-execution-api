package com.spin.transactions.service;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.exception.ConcurrentTransactionUpdateException;
import com.spin.transactions.model.PagedResult;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.model.TransactionStatus;

/**
 * Orchestrates the execution and query of financial transactions.
 *
 * <p>The service sits between the controller (HTTP boundary) and the repository
 * plus the {@link ProviderService} (persistence and external I/O). It owns the
 * write-ahead flow and the mapping of provider outcomes to the persisted state
 * machine {@link TransactionStatus#PENDING} →
 * {@link TransactionStatus#EXECUTED EXECUTED} /
 * {@link TransactionStatus#REJECTED REJECTED} /
 * {@link TransactionStatus#FAILED FAILED}.
 */
public interface TransactionService {

    /**
     * Executes a transaction end-to-end.
     *
     * <p><b>Contract</b>:
     * <ol>
     *   <li>All {@link com.spin.transactions.service.rule.TransactionRule rules}
     *       are evaluated first. If any fails, the method throws
     *       {@link BusinessRuleViolationException} and <b>no row is persisted</b>.</li>
     *   <li>Otherwise the transaction is inserted in {@code PENDING} and
     *       committed <b>before</b> calling the provider (write-ahead), so a
     *       crash mid-call leaves the row reconcilable.</li>
     *   <li>The provider is called through {@link ProviderService#execute}. The
     *       returned {@link Transaction} is always in a terminal state
     *       ({@code EXECUTED}, {@code REJECTED} or {@code FAILED}) — provider
     *       failures are captured on the row, they do not propagate as
     *       exceptions from this method.</li>
     *   <li>If {@code command} carries an idempotency key and the row already
     *       exists in a terminal state (idempotent hit resolved by the
     *       repository), the pre-existing row is returned and the provider is
     *       <b>not</b> called again.</li>
     * </ol>
     *
     * <p>The method is <b>not</b> {@code @Transactional} on purpose: keeping a
     * DB connection open across the external HTTP call would exhaust the pool.
     * Each persistence step runs as its own short auto-commit transaction.
     *
     * @throws BusinessRuleViolationException     if a rule blocks the transaction (before any persistence)
     * @throws ConcurrentTransactionUpdateException if another actor moved the row out of {@code PENDING}
     *                                              before the terminal update landed
     */
    Transaction execute(TransactionCommand command);

    /**
     * Paginated read with optional filters.
     *
     * <p>Pagination is computed without a {@code COUNT(*)}: the repository is
     * asked for {@code limit + 1} rows and {@code hasNext} is set to {@code true}
     * if the extra row came back.
     *
     * @param page  zero-based page index; must be {@code >= 0}
     * @param limit page size; must be in {@code [1, 100]}
     * @throws IllegalArgumentException if {@code page} or {@code limit} are out of range
     */
    PagedResult<Transaction> find(TransactionFilter filter, int page, int limit);
}
