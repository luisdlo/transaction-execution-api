package com.spin.transactions.controller;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.exception.GlobalExceptionHandler;
import com.spin.transactions.model.PagedResult;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.model.TransactionStatus;
import com.spin.transactions.model.TransactionType;
import com.spin.transactions.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import(GlobalExceptionHandler.class)
class TransactionControllerTest {

    private static final UUID TX_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-03-15T10:30:00Z");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TransactionService service;

    private static Transaction transaction(TransactionStatus status,
                                           String providerTxnId,
                                           BigDecimal balanceAfter,
                                           String failureCode,
                                           String failureMessage) {
        return new Transaction(
                TX_ID,
                "acc-123456",
                TransactionType.CREDIT,
                new BigDecimal("1500.00"),
                "MXN",
                "Test transaction",
                status,
                providerTxnId,
                balanceAfter,
                failureCode,
                failureMessage,
                null,
                NOW,
                NOW);
    }

    private static String validRequestBody() {
        return """
                {
                  "accountId": "acc-123456",
                  "type": "CREDIT",
                  "amount": 1500.00,
                  "currency": "MXN",
                  "description": "Test transaction"
                }
                """;
    }

    @Test
    void post_returns201_whenTransactionIsExecuted() throws Exception {
        Transaction executed = transaction(
                TransactionStatus.EXECUTED, "provider-txn-1", new BigDecimal("5500.00"), null, null);
        when(service.execute(any(TransactionCommand.class))).thenReturn(executed);

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/transactions/" + TX_ID))
                .andExpect(jsonPath("$.id").value(TX_ID.toString()))
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.accountId").value("acc-123456"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("MXN"))
                .andExpect(jsonPath("$.providerTransactionId").value("provider-txn-1"))
                .andExpect(jsonPath("$.balanceAfter").value(5500.00));
    }

    @Test
    void post_returns201_whenProviderRejected() throws Exception {
        // Critical: a business rejection is NOT a 4xx. The transaction was created,
        // recorded and durable — the caller reads `status` to know it was REJECTED.
        Transaction rejected = transaction(
                TransactionStatus.REJECTED, null, null, "INSUFFICIENT_FUNDS", "Not enough balance");
        when(service.execute(any(TransactionCommand.class))).thenReturn(rejected);

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.providerTransactionId").doesNotExist());
    }

    @Test
    void post_returns201_whenProviderFailed() throws Exception {
        Transaction failed = transaction(
                TransactionStatus.FAILED, null, null, null, "Read timeout waiting for provider response");
        when(service.execute(any(TransactionCommand.class))).thenReturn(failed);

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void post_returns400_whenAmountIsNegative() throws Exception {
        String body = """
                {
                  "accountId": "acc-123456",
                  "type": "CREDIT",
                  "amount": -10.00,
                  "currency": "MXN"
                }
                """;

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')]").exists());

        verifyNoInteractions(service);
    }

    @Test
    void post_returns400_whenAccountIdIsMissing() throws Exception {
        String body = """
                {
                  "type": "CREDIT",
                  "amount": 100.00,
                  "currency": "MXN"
                }
                """;

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'accountId')]").exists());

        verifyNoInteractions(service);
    }

    @Test
    void post_returns422_whenBusinessRuleIsViolated() throws Exception {
        when(service.execute(any(TransactionCommand.class)))
                .thenThrow(new BusinessRuleViolationException("MIN_AMOUNT", "amount below account minimum"));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIN_AMOUNT"))
                .andExpect(jsonPath("$.detail").value("amount below account minimum"));
    }

    @Test
    void post_normalizesCurrencyToUpperCase_beforeCallingService() throws Exception {
        Transaction executed = transaction(
                TransactionStatus.EXECUTED, "provider-txn-2", new BigDecimal("100.00"), null, null);
        when(service.execute(any(TransactionCommand.class))).thenReturn(executed);

        String body = """
                {
                  "accountId": "acc-123456",
                  "type": "CREDIT",
                  "amount": 100.00,
                  "currency": "mxn"
                }
                """;

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<TransactionCommand> captor = ArgumentCaptor.forClass(TransactionCommand.class);
        verify(service).execute(captor.capture());
        assertThat(captor.getValue().currency()).isEqualTo("MXN");
    }

    @Test
    void get_returns200_withPagedShape_whenNoFiltersProvided() throws Exception {
        Transaction row = transaction(
                TransactionStatus.EXECUTED, "provider-txn-3", new BigDecimal("77.00"), null, null);
        when(service.find(any(TransactionFilter.class), anyInt(), anyInt()))
                .thenReturn(new PagedResult<>(List.of(row), 0, 20, false));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(TX_ID.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void get_passesFilterFieldsThroughToService() throws Exception {
        when(service.find(any(TransactionFilter.class), anyInt(), anyInt()))
                .thenReturn(new PagedResult<>(List.of(), 3, 10, false));

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-9")
                        .param("status", "REJECTED")
                        .param("type", "DEBIT")
                        .param("page", "3")
                        .param("limit", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<TransactionFilter> filterCaptor = ArgumentCaptor.forClass(TransactionFilter.class);
        ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(service).find(filterCaptor.capture(), pageCaptor.capture(), limitCaptor.capture());

        assertThat(filterCaptor.getValue().accountId()).isEqualTo("acc-9");
        assertThat(filterCaptor.getValue().status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(filterCaptor.getValue().type()).isEqualTo(TransactionType.DEBIT);
        assertThat(pageCaptor.getValue()).isEqualTo(3);
        assertThat(limitCaptor.getValue()).isEqualTo(10);
    }

    @Test
    void post_returns400_whenBodyHasInvalidEnumValue() throws Exception {
        // The type "INVALID" is not a TransactionType. Jackson throws while binding
        // and Spring wraps it as HttpMessageNotReadableException — must map to 400,
        // not the generic 500 fallback.
        String body = """
                {
                  "accountId": "acc-1",
                  "type": "INVALID",
                  "amount": 100,
                  "currency": "MXN"
                }
                """;

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void get_returns400_whenQueryParamCannotBeConvertedToEnum() throws Exception {
        // ?status=NOPE triggers MethodArgumentTypeMismatchException when Spring
        // tries to bind the string to the TransactionStatus enum.
        mockMvc.perform(get("/transactions").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail",
                        org.hamcrest.Matchers.containsString("status")));

        verifyNoInteractions(service);
    }

    @Test
    void get_returns400_whenLimitIsInvalid() throws Exception {
        // The service is the one that enforces 1 <= limit <= 100 (validated in its
        // own tests). Here we just verify the advice maps IllegalArgumentException
        // to a 400 ProblemDetail with the offending message.
        when(service.find(any(TransactionFilter.class), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("limit must be between 1 and 100"));

        mockMvc.perform(get("/transactions").param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("limit must be between 1 and 100"));
    }
}
