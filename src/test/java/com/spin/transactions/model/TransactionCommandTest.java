package com.spin.transactions.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionCommandTest {

    private static final BigDecimal AMOUNT = new BigDecimal("1500.00");

    private static TransactionCommand commandWith(String accountId, BigDecimal amount, String currency) {
        return new TransactionCommand(
                accountId,
                TransactionType.CREDIT,
                amount,
                currency,
                "Transferencia recibida",
                null
        );
    }

    @Test
    void acceptsAValidCommand() {
        TransactionCommand command = commandWith("acc-123456", AMOUNT, "MXN");

        assertThat(command.accountId()).isEqualTo("acc-123456");
        assertThat(command.amount()).isEqualByComparingTo(AMOUNT);
    }

    @Test
    void allowsANullIdempotencyKey() {
        TransactionCommand command = commandWith("acc-123456", AMOUNT, "MXN");

        assertThat(command.idempotencyKey()).isNull();
    }

    @Test
    void rejectsANullAccountId() {
        assertThatThrownBy(() -> commandWith(null, AMOUNT, "MXN"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("accountId");
    }

    @Test
    void rejectsABlankAccountId() {
        assertThatThrownBy(() -> commandWith("   ", AMOUNT, "MXN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
    }

    @Test
    void rejectsANullAmount() {
        assertThatThrownBy(() -> commandWith("acc-123456", null, "MXN"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsAZeroAmount() {
        assertThatThrownBy(() -> commandWith("acc-123456", BigDecimal.ZERO, "MXN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> commandWith("acc-123456", new BigDecimal("-100.00"), "MXN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsABlankCurrency() {
        assertThatThrownBy(() -> commandWith("acc-123456", AMOUNT, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void doesNotApplyBusinessRules() {
        // El minimo de $1.00 y el limite de DEBIT son reglas de negocio configurables,
        // no invariantes del modelo. Viven en service/rule.
        TransactionCommand command = commandWith("acc-123456", new BigDecimal("0.50"), "USD");

        assertThat(command.amount()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(command.currency()).isEqualTo("USD");
    }
}