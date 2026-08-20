package com.spin.transactions.service.rule;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class MinimumAmountRuleTest {

    private static final BigDecimal MINIMUM = new BigDecimal("1.00");

    private MinimumAmountRule rule;

    @BeforeEach
    void setUp() {
        TransactionRuleProperties properties = new TransactionRuleProperties(
                MINIMUM,
                new BigDecimal("10000.00"),
                "MXN"
        );
        rule = new MinimumAmountRule(properties);
    }

    private static TransactionCommand commandWithAmount(BigDecimal amount) {
        return new TransactionCommand(
                "acc-123456",
                TransactionType.CREDIT,
                amount,
                "MXN",
                "Test",
                null
        );
    }

    @Test
    void given_anAmountAboveTheMinimum_when_validating_then_itPasses() {
        TransactionCommand command = commandWithAmount(new BigDecimal("1.01"));

        assertThatCode(() -> rule.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void given_anAmountEqualToTheMinimum_when_validating_then_itIsRejected() {
        TransactionCommand command = commandWithAmount(new BigDecimal("1.00"));

        BusinessRuleViolationException ex = catchThrowableOfType(
                BusinessRuleViolationException.class,
                () -> rule.validate(command)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo("AMOUNT_BELOW_MINIMUM");
    }

    @Test
    void given_anAmountBelowTheMinimum_when_validating_then_itIsRejected() {
        TransactionCommand command = commandWithAmount(new BigDecimal("0.50"));

        BusinessRuleViolationException ex = catchThrowableOfType(
                BusinessRuleViolationException.class,
                () -> rule.validate(command)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo("AMOUNT_BELOW_MINIMUM");
    }

    @Test
    void given_anAmountBelowTheMinimum_when_validating_then_theMessageIncludesTheValueAndTheLimit() {
        TransactionCommand command = commandWithAmount(new BigDecimal("0.50"));

        BusinessRuleViolationException ex = catchThrowableOfType(
                BusinessRuleViolationException.class,
                () -> rule.validate(command)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("0.50").contains("1.00");
    }
}
