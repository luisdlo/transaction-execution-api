package com.spin.transactions.service;

import com.spin.transactions.model.PagedResult;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.repository.TransactionRepository;
import com.spin.transactions.service.provider.ProviderClient;
import com.spin.transactions.service.provider.ProviderExecution;
import com.spin.transactions.service.provider.ProviderRejectedException;
import com.spin.transactions.service.provider.ProviderUnavailableException;
import com.spin.transactions.service.provider.ProviderUnknownStateException;
import com.spin.transactions.service.rule.TransactionRule;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class DefaultTransactionService implements TransactionService {

    private static final int MAX_LIMIT = 100;

    private final List<TransactionRule> rules;
    private final TransactionRepository repository;
    private final ProviderClient providerClient;
    private final Clock clock;

    public DefaultTransactionService(List<TransactionRule> rules,
                                     TransactionRepository repository,
                                     ProviderClient providerClient,
                                     Clock clock) {
        this.rules = rules;
        this.repository = repository;
        this.providerClient = providerClient;
        this.clock = clock;
    }

    // Deliberately NOT @Transactional: holding a DB connection open across the
    // external HTTP call would exhaust the pool at millions of daily transactions.
    // Each persistence op runs as its own short auto-commit transaction, which is
    // what makes the write-ahead pattern below reconcilable if the process dies.
    @Override
    public Transaction execute(TransactionCommand command) {
        for (TransactionRule rule : rules) {
            rule.validate(command);
        }
        Transaction saved = repository.save(Transaction.pending(command, Instant.now(clock)));
        return callProviderAndPersistOutcome(saved);
    }

    private Transaction callProviderAndPersistOutcome(Transaction saved) {
        Instant now;
        try {
            ProviderExecution execution = providerClient.execute(saved);
            now = Instant.now(clock);
            return repository.markExecuted(
                    saved.id(),
                    execution.providerTransactionId(),
                    execution.balanceAfter(),
                    now);
        } catch (ProviderRejectedException e) {
            now = Instant.now(clock);
            return repository.markRejected(saved.id(), e.code(), e.getMessage(), now);
        } catch (ProviderUnavailableException | ProviderUnknownStateException e) {
            // Both terminate the transaction in FAILED but arrive from opposite paths:
            //   Unavailable   — retries exhausted with NO effect on the provider; the
            //                   transaction is safely reattempt-able by a human/replay.
            //   UnknownState  — the request left the client but the outcome is opaque
            //                   (read timeout, unreadable 200); needs manual
            //                   reconciliation against the provider before any replay.
            now = Instant.now(clock);
            return repository.markFailed(saved.id(), e.getMessage(), now);
        }
        // ConcurrentTransactionUpdateException from any markX propagates by design:
        // some other actor already moved this row out of PENDING and we must not
        // overwrite them.
    }

    @Override
    public PagedResult<Transaction> find(TransactionFilter filter, int page, int limit) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        // Multiply as long: with page and limit each bounded by int, a request deep
        // into the pagination could otherwise overflow (e.g. page=Integer.MAX_VALUE/50)
        // and silently produce a negative offset. The repository takes a long and
        // Postgres OFFSET accepts bigint, so the widened value flows through untouched.
        long offset = (long) page * (long) limit;
        // Ask for limit+1 to know whether there is a next page without a COUNT(*).
        List<Transaction> rows = repository.findByFilters(filter, limit + 1, offset);
        boolean hasNext = rows.size() > limit;
        List<Transaction> items = hasNext ? rows.subList(0, limit) : rows;
        return new PagedResult<>(items, page, limit, hasNext);
    }
}
