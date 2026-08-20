package com.spin.transactions.repository;

import com.spin.transactions.exception.ConcurrentTransactionUpdateException;
import com.spin.transactions.exception.TransactionNotFoundException;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.model.TransactionStatus;
import com.spin.transactions.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class JdbcTransactionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant CREATED_AT = Instant.parse("2026-03-15T10:30:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-03-15T10:30:05Z");

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE transactions").update();
    }

    private static TransactionCommand command(String accountId, TransactionType type,
                                              String amount, String idempotencyKey) {
        return new TransactionCommand(
                accountId, type, new BigDecimal(amount), "MXN", "Test transaction", idempotencyKey);
    }

    private Transaction persistPending(String accountId, TransactionType type, String amount) {
        return repository.save(Transaction.pending(command(accountId, type, amount, null), CREATED_AT));
    }


    @Nested
    @DisplayName("save and read back")
    class RoundTrip {

        @Test
        void persistsAndRetrievesEveryField() {
            Transaction saved = repository.save(
                    Transaction.pending(command("acc-123456", TransactionType.CREDIT, "1500.00", "key-abc"), CREATED_AT));

            Transaction found = repository.findById(saved.id()).orElseThrow();

            assertThat(found.id()).isEqualTo(saved.id());
            assertThat(found.accountId()).isEqualTo("acc-123456");
            assertThat(found.type()).isEqualTo(TransactionType.CREDIT);
            assertThat(found.currency()).isEqualTo("MXN");
            assertThat(found.description()).isEqualTo("Test transaction");
            assertThat(found.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(found.idempotencyKey()).isEqualTo("key-abc");
        }

        @Test
        @DisplayName("preserves the decimal scale required by NUMERIC(19,4)")
        void preservesTheAmountScale() {
            Transaction saved = persistPending("acc-1", TransactionType.CREDIT, "1500.1234");

            Transaction found = repository.findById(saved.id()).orElseThrow();

            assertThat(found.amount()).isEqualByComparingTo(new BigDecimal("1500.1234"));
        }

        @Test
        @DisplayName("round trips Instant through TIMESTAMPTZ without losing precision")
        void preservesTheTimestamps() {
            Transaction saved = persistPending("acc-1", TransactionType.CREDIT, "1500.00");

            Transaction found = repository.findById(saved.id()).orElseThrow();

            assertThat(found.createdAt()).isEqualTo(CREATED_AT);
            assertThat(found.updatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        void leavesProviderResultColumnsNull() {
            Transaction saved = persistPending("acc-1", TransactionType.CREDIT, "1500.00");

            Transaction found = repository.findById(saved.id()).orElseThrow();

            assertThat(found.providerTransactionId()).isNull();
            assertThat(found.balanceAfter()).isNull();
            assertThat(found.failureCode()).isNull();
            assertThat(found.failureMessage()).isNull();
        }

        @Test
        void returnsEmptyForAnUnknownId() {
            assertThat(repository.findById(UUID.randomUUID())).isEmpty();
        }
    }


    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the partial unique index prevents two rows with the same key")
        void returnsTheExistingRowOnDuplicateKey() {
            Transaction first = repository.save(
                    Transaction.pending(command("acc-1", TransactionType.CREDIT, "1500.00", "key-abc"), CREATED_AT));

            Transaction second = repository.save(
                    Transaction.pending(command("acc-1", TransactionType.CREDIT, "9999.00", "key-abc"), UPDATED_AT));

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(second.amount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        }

        @Test
        @DisplayName("the same key on a different account is not a duplicate")
        void allowsTheSameKeyOnADifferentAccount() {
            repository.save(Transaction.pending(
                    command("acc-1", TransactionType.CREDIT, "1500.00", "key-abc"), CREATED_AT));

            Transaction other = repository.save(Transaction.pending(
                    command("acc-2", TransactionType.CREDIT, "1500.00", "key-abc"), CREATED_AT));

            assertThat(repository.findById(other.id())).isPresent();
        }

        @Test
        @DisplayName("null keys do not collide: the unique index is partial")
        void allowsManyTransactionsWithoutAnIdempotencyKey() {
            persistPending("acc-1", TransactionType.CREDIT, "1500.00");
            persistPending("acc-1", TransactionType.CREDIT, "1500.00");

            List<Transaction> all = repository.findByFilters(
                    new TransactionFilter("acc-1", null, null), 10, 0);

            assertThat(all).hasSize(2);
        }

        @Test
        void findsATransactionByItsIdempotencyKey() {
            Transaction saved = repository.save(Transaction.pending(
                    command("acc-1", TransactionType.CREDIT, "1500.00", "key-abc"), CREATED_AT));

            Optional<Transaction> found = repository.findByIdempotencyKey("acc-1", "key-abc");

            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(saved.id());
            assertThat(found.get().idempotencyKey()).isEqualTo("key-abc");
            assertThat(found.get().amount()).isEqualByComparingTo(saved.amount());
        }

        @Test
        @DisplayName("a duplicate id without an idempotency key is a real bug and must surface")
        void rethrowsDuplicateKeyWhenThereIsNoIdempotencyKey() {
            Transaction saved = persistPending("acc-1", TransactionType.CREDIT, "1500.00");

            assertThatThrownBy(() -> repository.save(saved))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }


    @Nested
    @DisplayName("state transitions")
    class StateTransitions {

        @Test
        void marksATransactionAsExecuted() {
            Transaction pending = persistPending("acc-1", TransactionType.CREDIT, "1500.00");

            Transaction executed = repository.markExecuted(
                    pending.id(), "txn-789", new BigDecimal("5500.00"), UPDATED_AT);

            assertThat(executed.status()).isEqualTo(TransactionStatus.EXECUTED);
            assertThat(executed.providerTransactionId()).isEqualTo("txn-789");
            assertThat(executed.balanceAfter()).isEqualByComparingTo(new BigDecimal("5500.00"));
            assertThat(executed.updatedAt()).isEqualTo(UPDATED_AT);
            assertThat(executed.createdAt()).isEqualTo(CREATED_AT);
        }

        @Test
        void marksATransactionAsRejected() {
            Transaction pending = persistPending("acc-1", TransactionType.DEBIT, "500.00");

            Transaction rejected = repository.markRejected(
                    pending.id(), "INSUFFICIENT_FUNDS", "Not enough balance", UPDATED_AT);

            assertThat(rejected.status()).isEqualTo(TransactionStatus.REJECTED);
            assertThat(rejected.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(rejected.failureMessage()).isEqualTo("Not enough balance");
        }

        @Test
        void marksATransactionAsFailed() {
            Transaction pending = persistPending("acc-1", TransactionType.CREDIT, "1500.00");

            Transaction failed = repository.markFailed(pending.id(), "Read timeout", UPDATED_AT);

            assertThat(failed.status()).isEqualTo(TransactionStatus.FAILED);
            assertThat(failed.failureMessage()).isEqualTo("Read timeout");
            assertThat(failed.failureCode()).isNull();
        }

        @Test
        @DisplayName("the conditional UPDATE guards against a second transition")
        void rejectsATransitionOnATransactionThatIsNoLongerPending() {
            Transaction pending = persistPending("acc-1", TransactionType.CREDIT, "1500.00");
            repository.markExecuted(pending.id(), "txn-789", new BigDecimal("5500.00"), UPDATED_AT);

            assertThatThrownBy(() -> repository.markExecuted(
                    pending.id(), "txn-999", new BigDecimal("1000.00"), UPDATED_AT))
                    .isInstanceOf(ConcurrentTransactionUpdateException.class);
        }

        @Test
        @DisplayName("a missing row is reported as not found, not as a concurrency conflict")
        void reportsAnUnknownIdAsNotFound() {
            assertThatThrownBy(() -> repository.markExecuted(
                    UUID.randomUUID(), "txn-789", new BigDecimal("5500.00"), UPDATED_AT))
                    .isInstanceOf(TransactionNotFoundException.class);
        }
    }


    @Nested
    @DisplayName("filters and pagination")
    class Filters {

        @BeforeEach
        void seed() {
            repository.markExecuted(
                    persistPending("acc-1", TransactionType.CREDIT, "100.00").id(),
                    "txn-1", new BigDecimal("100.00"), UPDATED_AT);
            repository.markRejected(
                    persistPending("acc-1", TransactionType.DEBIT, "200.00").id(),
                    "INSUFFICIENT_FUNDS", "No balance", UPDATED_AT);
            persistPending("acc-2", TransactionType.CREDIT, "300.00");
        }

        @Test
        void returnsEverythingWhenNoFilterIsGiven() {
            List<Transaction> result = repository.findByFilters(
                    new TransactionFilter(null, null, null), 10, 0);

            assertThat(result).hasSize(3);
        }

        @Test
        void filtersByAccountId() {
            List<Transaction> result = repository.findByFilters(
                    new TransactionFilter("acc-1", null, null), 10, 0);

            assertThat(result).hasSize(2)
                    .allSatisfy(transaction -> assertThat(transaction.accountId()).isEqualTo("acc-1"));
        }

        @Test
        void filtersByStatus() {
            List<Transaction> result = repository.findByFilters(
                    new TransactionFilter(null, TransactionStatus.EXECUTED, null), 10, 0);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().status()).isEqualTo(TransactionStatus.EXECUTED);
        }

        @Test
        void filtersByType() {
            List<Transaction> result = repository.findByFilters(
                    new TransactionFilter(null, null, TransactionType.DEBIT), 10, 0);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().type()).isEqualTo(TransactionType.DEBIT);
        }

        @Test
        void combinesFiltersWithAnd() {
            List<Transaction> result = repository.findByFilters(
                    new TransactionFilter("acc-1", TransactionStatus.REJECTED, TransactionType.DEBIT), 10, 0);

            assertThat(result).hasSize(1);
        }

        @Test
        void returnsEmptyWhenNothingMatches() {
            List<Transaction> result = repository.findByFilters(
                    new TransactionFilter("acc-unknown", null, null), 10, 0);

            assertThat(result).isEmpty();
        }

        @Test
        void appliesTheLimit() {
            List<Transaction> result = repository.findByFilters(
                    new TransactionFilter(null, null, null), 2, 0);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("consecutive pages do not overlap")
        void appliesTheOffset() {
            List<Transaction> firstPage = repository.findByFilters(
                    new TransactionFilter(null, null, null), 2, 0);
            List<Transaction> secondPage = repository.findByFilters(
                    new TransactionFilter(null, null, null), 2, 2);

            assertThat(secondPage).hasSize(1);
            assertThat(secondPage.getFirst().id())
                    .isNotIn(firstPage.stream().map(Transaction::id).toList());
        }
    }
}
