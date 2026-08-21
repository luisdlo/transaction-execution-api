package com.spin.transactions.repository;

import com.spin.transactions.exception.ConcurrentTransactionUpdateException;
import com.spin.transactions.exception.TransactionNotFoundException;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.model.TransactionStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransactionRepository implements TransactionRepository {

    private static final String INSERT_SQL = """
            INSERT INTO transactions (
                id, account_id, type, amount, currency, description, status,
                provider_transaction_id, balance_after,
                failure_code, failure_message,
                idempotency_key,
                created_at, updated_at
            ) VALUES (
                :id, :accountId, :type, :amount, :currency, :description, :status,
                :providerTransactionId, :balanceAfter,
                :failureCode, :failureMessage,
                :idempotencyKey,
                :createdAt, :updatedAt
            )
            """;

    private static final String SELECT_ALL_COLUMNS = """
            SELECT id, account_id, type, amount, currency, description, status,
                   provider_transaction_id, balance_after,
                   failure_code, failure_message, idempotency_key,
                   created_at, updated_at
              FROM transactions
            """;

    private static final String SELECT_BY_ID_SQL = SELECT_ALL_COLUMNS + " WHERE id = :id";

    private static final String SELECT_BY_IDEMPOTENCY_KEY_SQL = SELECT_ALL_COLUMNS +
            " WHERE account_id = :accountId AND idempotency_key = :idempotencyKey";

    private static final String UPDATE_EXECUTED_SQL = """
            UPDATE transactions
               SET status = :status,
                   provider_transaction_id = :providerTransactionId,
                   balance_after = :balanceAfter,
                   updated_at = :updatedAt
             WHERE id = :id AND status = 'PENDING'
            """;

    private static final String UPDATE_REJECTED_SQL = """
            UPDATE transactions
               SET status = :status,
                   failure_code = :failureCode,
                   failure_message = :failureMessage,
                   updated_at = :updatedAt
             WHERE id = :id AND status = 'PENDING'
            """;

    private static final String UPDATE_FAILED_SQL = """
            UPDATE transactions
               SET status = :status,
                   failure_message = :failureMessage,
                   updated_at = :updatedAt
             WHERE id = :id AND status = 'PENDING'
            """;

    private final JdbcClient jdbcClient;

    public JdbcTransactionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Transaction save(Transaction transaction) {
        try {
            jdbcClient.sql(INSERT_SQL)
                    .param("id", transaction.id())
                    .param("accountId", transaction.accountId())
                    .param("type", transaction.type().name())
                    .param("amount", transaction.amount())
                    .param("currency", transaction.currency())
                    .param("description", transaction.description())
                    .param("status", transaction.status().name())
                    .param("providerTransactionId", transaction.providerTransactionId())
                    .param("balanceAfter", transaction.balanceAfter())
                    .param("failureCode", transaction.failureCode())
                    .param("failureMessage", transaction.failureMessage())
                    .param("idempotencyKey", transaction.idempotencyKey())
                    .param("createdAt", transaction.createdAt().atOffset(ZoneOffset.UTC))
                    .param("updatedAt", transaction.updatedAt().atOffset(ZoneOffset.UTC))
                    .update();
            return transaction;
        } catch (DuplicateKeyException e) {
            // Idempotency race: two requests with the same (accountId, idempotencyKey)
            // hit the DB concurrently. The second loses the insert on the partial unique
            // index; we return the row that actually persisted so both callers get the
            // same HTTP response. Without an idempotencyKey no such race is possible,
            // so a duplicate here would be a real bug (UUID collision) and we rethrow.
            if (transaction.idempotencyKey() == null) {
                throw e;
            }
            return findByIdempotencyKey(transaction.accountId(), transaction.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "duplicate key on insert but existing row not found for "
                                    + transaction.accountId() + "/" + transaction.idempotencyKey()));
        }
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jdbcClient.sql(SELECT_BY_ID_SQL)
                .param("id", id)
                .query(TransactionRowMapper.INSTANCE)
                .optional();
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String accountId, String idempotencyKey) {
        return jdbcClient.sql(SELECT_BY_IDEMPOTENCY_KEY_SQL)
                .param("accountId", accountId)
                .param("idempotencyKey", idempotencyKey)
                .query(TransactionRowMapper.INSTANCE)
                .optional();
    }

    @Override
    public List<Transaction> findByFilters(TransactionFilter filter, int limit, long offset) {
        StringBuilder sql = new StringBuilder(SELECT_ALL_COLUMNS).append(" WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        if (filter.accountId() != null) {
            sql.append(" AND account_id = :accountId");
            params.put("accountId", filter.accountId());
        }
        if (filter.status() != null) {
            sql.append(" AND status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.type() != null) {
            sql.append(" AND type = :type");
            params.put("type", filter.type().name());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbcClient.sql(sql.toString())
                .params(params)
                .query(TransactionRowMapper.INSTANCE)
                .list();
    }

    @Override
    public Transaction markExecuted(UUID id, String providerTransactionId, BigDecimal balanceAfter, Instant now) {
        return applyStateTransition(id, jdbcClient.sql(UPDATE_EXECUTED_SQL)
                .param("id", id)
                .param("status", TransactionStatus.EXECUTED.name())
                .param("providerTransactionId", providerTransactionId)
                .param("balanceAfter", balanceAfter)
                .param("updatedAt", now.atOffset(ZoneOffset.UTC)));
    }

    @Override
    public Transaction markRejected(UUID id, String failureCode, String failureMessage, Instant now) {
        return applyStateTransition(id, jdbcClient.sql(UPDATE_REJECTED_SQL)
                .param("id", id)
                .param("status", TransactionStatus.REJECTED.name())
                .param("failureCode", failureCode)
                .param("failureMessage", failureMessage)
                .param("updatedAt", now.atOffset(ZoneOffset.UTC)));
    }

    @Override
    public Transaction markFailed(UUID id, String failureMessage, Instant now) {
        return applyStateTransition(id, jdbcClient.sql(UPDATE_FAILED_SQL)
                .param("id", id)
                .param("status", TransactionStatus.FAILED.name())
                .param("failureMessage", failureMessage)
                .param("updatedAt", now.atOffset(ZoneOffset.UTC)));
    }

    private Transaction applyStateTransition(UUID id, JdbcClient.StatementSpec update) {
        int rows = update.update();
        if (rows != 1) {
            if (findById(id).isPresent()) {
                throw new ConcurrentTransactionUpdateException(id);
            }
            throw new TransactionNotFoundException(id);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException(
                "transaction " + id + " was updated but disappeared before re-read"));
    }
}
