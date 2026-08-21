package com.spin.transactions.service.rule.impl;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.service.rule.TransactionRule;
import com.spin.transactions.service.rule.TransactionRuleProperties;
import org.springframework.stereotype.Component;

@Component
public class SupportedCurrencyRule implements TransactionRule {

    private static final String CODE = "UNSUPPORTED_CURRENCY";

    private final TransactionRuleProperties properties;

    public SupportedCurrencyRule(TransactionRuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void validate(TransactionCommand command) {
        if (!command.currency().equalsIgnoreCase(properties.supportedCurrency())) {
            throw new BusinessRuleViolationException(
                    CODE,
                    "currency " + command.currency()
                            + " is not supported; expected " + properties.supportedCurrency()
            );
        }
    }
}
