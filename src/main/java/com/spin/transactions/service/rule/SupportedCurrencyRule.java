package com.spin.transactions.service.rule;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;
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
