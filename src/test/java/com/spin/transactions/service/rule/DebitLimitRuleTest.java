package com.spin.transactions.service.rule;

import com.spin.transactions.service.rule.impl.DebitLimitRule;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DebitLimitRuleTest {

    private static final BigDecimal DEBIT_MAX = new BigDecimal("10000.00");

    private DebitLimitRule rule;

    @BeforeEach
    void setUp() {
        TransactionRuleProperties properties = new TransactionRuleProperties(
                new BigDecimal("1.00"),
                DEBIT_MAX,
                "MXN"
        );
        rule = new DebitLimitRule(properties);
    }

    private static TransactionCommand command(TransactionType type, BigDecimal amount) {
        return new TransactionCommand(
                "acc-123456",
                type,
                amount,
                "MXN",
                "Test",
                null
        );
    }

    @Test
    void given_aCreditTransactionFarAboveTheDebitLimit_when_validating_then_itPasses() {
        TransactionCommand command = command(TransactionType.CREDIT, new BigDecimal("50000.00"));

        assertThatCode(() -> rule.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void given_aDebitBelowTheLimit_when_validating_then_itPasses() {
        TransactionCommand command = command(TransactionType.DEBIT, new BigDecimal("9999.99"));

        assertThatCode(() -> rule.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void given_aDebitEqualToTheLimit_when_validating_then_itPasses() {
        TransactionCommand command = command(TransactionType.DEBIT, DEBIT_MAX);

        assertThatCode(() -> rule.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void given_aDebitAboveTheLimit_when_validating_then_itIsRejected() {
        TransactionCommand command = command(TransactionType.DEBIT, new BigDecimal("15000.00"));

        BusinessRuleViolationException ex = catchThrowableOfType(
                BusinessRuleViolationException.class,
                () -> rule.validate(command)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo("DEBIT_LIMIT_EXCEEDED");
    }

    @Test
    void given_aDebitAboveTheLimit_when_validating_then_theMessageMatchesTheExpectedFormat() {
        TransactionCommand command = command(TransactionType.DEBIT, new BigDecimal("15000.00"));

        BusinessRuleViolationException ex = catchThrowableOfType(
                BusinessRuleViolationException.class,
                () -> rule.validate(command)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage())
                .isEqualTo("DEBIT amount 15000.00 exceeds the maximum of 10000.00");
    }
}
