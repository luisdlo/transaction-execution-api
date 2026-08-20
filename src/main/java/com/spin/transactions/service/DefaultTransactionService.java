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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class DefaultTransactionService implements TransactionService {

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

        Transaction pending = Transaction.pending(command, Instant.now(clock));

        if (command.idempotencyKey() != null) {
            try {
                Transaction saved = repository.save(pending);
                return callProviderAndPersistOutcome(saved);
            } catch (DuplicateKeyException e) {
                // Idempotent race: the (accountId, idempotencyKey) unique index rejected
                // the insert. Return the row that actually persisted so both callers see
                // the same result the original request produced — do NOT call the
                // provider again, that would double-charge.
                return repository.findByIdempotencyKey(command.accountId(), command.idempotencyKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "duplicate key on insert but existing row not found for "
                                        + command.accountId() + "/" + command.idempotencyKey()));
            }
        }

        Transaction saved = repository.save(pending);
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
        // Ask for limit+1 to know whether there is a next page without a COUNT(*).
        List<Transaction> rows = repository.findByFilters(filter, limit + 1, page * limit);
        boolean hasNext = rows.size() > limit;
        List<Transaction> items = hasNext ? rows.subList(0, limit) : rows;
        return new PagedResult<>(items, page, limit, hasNext);
    }
}
