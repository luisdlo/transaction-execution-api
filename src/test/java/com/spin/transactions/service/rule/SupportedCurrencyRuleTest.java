package com.spin.transactions.service.rule;

import com.spin.transactions.service.rule.impl.SupportedCurrencyRule;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SupportedCurrencyRuleTest {

    private SupportedCurrencyRule rule;

    @BeforeEach
    void setUp() {
        TransactionRuleProperties properties = new TransactionRuleProperties(
                new BigDecimal("1.00"),
                new BigDecimal("10000.00"),
                "MXN"
        );
        rule = new SupportedCurrencyRule(properties);
    }

    private static TransactionCommand commandWithCurrency(String currency) {
        return new TransactionCommand(
                "acc-123456",
                TransactionType.CREDIT,
                new BigDecimal("100.00"),
                currency,
                "Test",
                null
        );
    }

    @Test
    void given_theSupportedCurrencyInUpperCase_when_validating_then_itPasses() {
        TransactionCommand command = commandWithCurrency("MXN");

        assertThatCode(() -> rule.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void given_theSupportedCurrencyInLowerCase_when_validating_then_itPasses() {
        TransactionCommand command = commandWithCurrency("mxn");

        assertThatCode(() -> rule.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void given_theSupportedCurrencyInMixedCase_when_validating_then_itPasses() {
        TransactionCommand command = commandWithCurrency("MxN");

        assertThatCode(() -> rule.validate(command)).doesNotThrowAnyException();
    }

    @Test
    void given_anUnsupportedCurrency_when_validating_then_itIsRejected() {
        TransactionCommand command = commandWithCurrency("USD");

        BusinessRuleViolationException ex = catchThrowableOfType(
                BusinessRuleViolationException.class,
                () -> rule.validate(command)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void given_anUnsupportedCurrency_when_validating_then_theMessageIncludesBothCurrencies() {
        TransactionCommand command = commandWithCurrency("USD");

        BusinessRuleViolationException ex = catchThrowableOfType(
                BusinessRuleViolationException.class,
                () -> rule.validate(command)
        );

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("USD").contains("MXN");
    }
}
