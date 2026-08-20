package com.spin.transactions.repository;

import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {

    Transaction save(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    Optional<Transaction> findByIdempotencyKey(String accountId, String idempotencyKey);

    List<Transaction> findByFilters(TransactionFilter filter, int limit, int offset);

    Transaction markExecuted(UUID id, String providerTransactionId, BigDecimal balanceAfter, Instant now);

    Transaction markRejected(UUID id, String failureCode, String failureMessage, Instant now);

    Transaction markFailed(UUID id, String failureMessage, Instant now);
}
