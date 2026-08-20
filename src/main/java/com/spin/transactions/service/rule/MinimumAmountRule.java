package com.spin.transactions.service.rule;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;
import org.springframework.stereotype.Component;

@Component
public class MinimumAmountRule implements TransactionRule {

    private static final String CODE = "AMOUNT_BELOW_MINIMUM";

    private final TransactionRuleProperties properties;

    public MinimumAmountRule(TransactionRuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void validate(TransactionCommand command) {
        if (command.amount().compareTo(properties.minimumAmount()) <= 0) {
            throw new BusinessRuleViolationException(
                    CODE,
                    "amount " + command.amount().toPlainString()
                            + " must be greater than the minimum of " + properties.minimumAmount().toPlainString()
            );
        }
    }
}
