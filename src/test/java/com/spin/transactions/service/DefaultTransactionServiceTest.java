package com.spin.transactions.service;

import com.spin.transactions.service.impl.DefaultTransactionService;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.PagedResult;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.model.TransactionStatus;
import com.spin.transactions.model.TransactionType;
import com.spin.transactions.repository.TransactionRepository;
import com.spin.transactions.provider.ProviderClient;
import com.spin.transactions.provider.ProviderExecution;
import com.spin.transactions.provider.ProviderRejectedException;
import com.spin.transactions.provider.ProviderUnavailableException;
import com.spin.transactions.provider.ProviderUnknownStateException;
import com.spin.transactions.service.rule.TransactionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T10:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID SAVED_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private TransactionRule ruleA;
    @Mock private TransactionRule ruleB;
    @Mock private TransactionRule ruleC;
    @Mock private TransactionRepository repository;
    @Mock private ProviderClient providerClient;

    private DefaultTransactionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultTransactionService(
                List.of(ruleA, ruleB, ruleC),
                repository,
                providerClient,
                CLOCK);
    }

    private static TransactionCommand command(String idempotencyKey) {
        return new TransactionCommand(
                "acc-123456",
                TransactionType.CREDIT,
                new BigDecimal("1500.00"),
                "MXN",
                "Test transaction",
                idempotencyKey);
    }

    // A "just-inserted" transaction with a known ID so the service uses SAVED_ID
    // on the state transition — Transaction.pending(...) generates a random UUID
    // that we would otherwise have to capture through an ArgumentCaptor.
    private static Transaction pendingFromRepo(TransactionCommand command, UUID id) {
        return new Transaction(
                id,
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
                NOW,
                NOW);
    }

    @Test
    @DisplayName("happy path: provider approves -> EXECUTED with provider fields")
    void execute_returnsExecuted_whenProviderApproves() {
        TransactionCommand cmd = command(null);
        Transaction saved = pendingFromRepo(cmd, SAVED_ID);
        BigDecimal balance = new BigDecimal("5500.00");
        Transaction executed = saved.markExecuted("provider-txn-1", balance, NOW);

        when(repository.save(any(Transaction.class))).thenReturn(saved);
        when(providerClient.execute(saved))
                .thenReturn(new ProviderExecution("provider-txn-1", balance, NOW));
        when(repository.markExecuted(SAVED_ID, "provider-txn-1", balance, NOW))
                .thenReturn(executed);

        Transaction result = service.execute(cmd);

        assertThat(result.status()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(result.providerTransactionId()).isEqualTo("provider-txn-1");
        assertThat(result.balanceAfter()).isEqualByComparingTo("5500.00");
    }

    @Test
    @DisplayName("rule violation short-circuits: repository and providerClient are never touched")
    void execute_stopsAtRule_andPersistsNothing() {
        TransactionCommand cmd = command(null);
        doThrow(new BusinessRuleViolationException("MIN_AMOUNT", "below minimum"))
                .when(ruleA).validate(cmd);

        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.code()).isEqualTo("MIN_AMOUNT"));

        verifyNoInteractions(repository);
        verifyNoInteractions(providerClient);
    }

    @Test
    @DisplayName("write-ahead: save() runs BEFORE the provider call")
    void execute_savesBeforeCallingProvider() {
        TransactionCommand cmd = command(null);
        Transaction saved = pendingFromRepo(cmd, SAVED_ID);
        BigDecimal balance = new BigDecimal("100.00");
        when(repository.save(any(Transaction.class))).thenReturn(saved);
        when(providerClient.execute(saved))
                .thenReturn(new ProviderExecution("provider-txn-2", balance, NOW));
        when(repository.markExecuted(eq(SAVED_ID), anyString(), any(), eq(NOW)))
                .thenReturn(saved.markExecuted("provider-txn-2", balance, NOW));

        service.execute(cmd);

        // If this order flips, a crash between provider and save would leave a real
        // charge with no local record — precisely what write-ahead exists to prevent.
        InOrder inOrder = inOrder(repository, providerClient);
        inOrder.verify(repository).save(any(Transaction.class));
        inOrder.verify(providerClient).execute(saved);
        inOrder.verify(repository).markExecuted(eq(SAVED_ID), eq("provider-txn-2"), any(), eq(NOW));
    }

    @Test
    @DisplayName("provider rejects -> REJECTED persisted, exception NOT propagated")
    void execute_persistsRejected_onProviderRejection() {
        TransactionCommand cmd = command(null);
        Transaction saved = pendingFromRepo(cmd, SAVED_ID);
        Transaction rejected = saved.markRejected("INSUFFICIENT_FUNDS", "Not enough balance", NOW);

        when(repository.save(any(Transaction.class))).thenReturn(saved);
        when(providerClient.execute(saved))
                .thenThrow(new ProviderRejectedException("INSUFFICIENT_FUNDS", "Not enough balance"));
        when(repository.markRejected(SAVED_ID, "INSUFFICIENT_FUNDS", "Not enough balance", NOW))
                .thenReturn(rejected);

        Transaction result = service.execute(cmd);

        assertThat(result.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(result.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.failureMessage()).isEqualTo("Not enough balance");
        verify(repository).markRejected(SAVED_ID, "INSUFFICIENT_FUNDS", "Not enough balance", NOW);
        verify(repository, never()).markExecuted(any(), any(), any(), any());
        verify(repository, never()).markFailed(any(), any(), any());
    }

    @Test
    @DisplayName("provider unavailable -> FAILED persisted, exception NOT propagated")
    void execute_persistsFailed_onProviderUnavailable() {
        TransactionCommand cmd = command(null);
        Transaction saved = pendingFromRepo(cmd, SAVED_ID);
        Transaction failed = saved.markFailed("Could not connect to provider", NOW);

        when(repository.save(any(Transaction.class))).thenReturn(saved);
        when(providerClient.execute(saved))
                .thenThrow(new ProviderUnavailableException(
                        "PROVIDER_UNREACHABLE", "Could not connect to provider"));
        when(repository.markFailed(SAVED_ID, "Could not connect to provider", NOW))
                .thenReturn(failed);

        Transaction result = service.execute(cmd);

        assertThat(result.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(result.failureMessage()).isEqualTo("Could not connect to provider");
    }

    @Test
    @DisplayName("provider unknown-state -> FAILED persisted, exception NOT propagated")
    void execute_persistsFailed_onProviderUnknownState() {
        TransactionCommand cmd = command(null);
        Transaction saved = pendingFromRepo(cmd, SAVED_ID);
        Transaction failed = saved.markFailed("Read timeout waiting for provider response", NOW);

        when(repository.save(any(Transaction.class))).thenReturn(saved);
        when(providerClient.execute(saved))
                .thenThrow(new ProviderUnknownStateException(
                        "Read timeout waiting for provider response"));
        when(repository.markFailed(SAVED_ID, "Read timeout waiting for provider response", NOW))
                .thenReturn(failed);

        Transaction result = service.execute(cmd);

        assertThat(result.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(result.failureMessage()).isEqualTo("Read timeout waiting for provider response");
    }

    @Test
    @DisplayName("every rule is applied, not just the first")
    void execute_appliesAllRules() {
        TransactionCommand cmd = command(null);
        Transaction saved = pendingFromRepo(cmd, SAVED_ID);
        when(repository.save(any(Transaction.class))).thenReturn(saved);
        when(providerClient.execute(saved))
                .thenReturn(new ProviderExecution("p", new BigDecimal("1.00"), NOW));
        when(repository.markExecuted(eq(SAVED_ID), anyString(), any(), eq(NOW))).thenReturn(saved);

        service.execute(cmd);

        verify(ruleA).validate(cmd);
        verify(ruleB).validate(cmd);
        verify(ruleC).validate(cmd);
    }

    @Test
    @DisplayName("find asks for limit+1, trims the extra row and flags hasNext=true")
    void find_trimsExtraRow_andFlagsHasNext() {
        TransactionFilter filter = new TransactionFilter("acc-1", TransactionStatus.EXECUTED, null);
        // Six rows returned for limit=5 proves the service asks for limit+1 (=6)
        // and trims the extra row while flipping hasNext to true.
        List<Transaction> rows = List.of(
                pendingFromRepo(command(null), UUID.randomUUID()),
                pendingFromRepo(command(null), UUID.randomUUID()),
                pendingFromRepo(command(null), UUID.randomUUID()),
                pendingFromRepo(command(null), UUID.randomUUID()),
                pendingFromRepo(command(null), UUID.randomUUID()),
                pendingFromRepo(command(null), UUID.randomUUID())
        );
        when(repository.findByFilters(filter, 6, 10)).thenReturn(rows);

        PagedResult<Transaction> result = service.find(filter, 2, 5);

        assertThat(result.items()).hasSize(5);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.limit()).isEqualTo(5);
        assertThat(result.hasNext()).isTrue();
        verify(repository).findByFilters(filter, 6, 10);
    }

    @Test
    @DisplayName("find rejects a negative page and never touches the repository")
    void find_rejectsNegativePage() {
        TransactionFilter filter = new TransactionFilter(null, null, null);

        assertThatThrownBy(() -> service.find(filter, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("find rejects limit=0 (must be >= 1)")
    void find_rejectsZeroLimit() {
        TransactionFilter filter = new TransactionFilter(null, null, null);

        assertThatThrownBy(() -> service.find(filter, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("find rejects a limit greater than MAX_LIMIT (100)")
    void find_rejectsLimitAboveMax() {
        TransactionFilter filter = new TransactionFilter(null, null, null);

        assertThatThrownBy(() -> service.find(filter, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        verifyNoInteractions(repository);
    }
}
