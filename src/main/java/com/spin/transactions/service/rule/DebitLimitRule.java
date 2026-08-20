package com.spin.transactions.service.rule;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionType;
import org.springframework.stereotype.Component;

@Component
public class DebitLimitRule implements TransactionRule {

    private static final String CODE = "DEBIT_LIMIT_EXCEEDED";

    private final TransactionRuleProperties properties;

    public DebitLimitRule(TransactionRuleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void validate(TransactionCommand command) {
        if (command.type() != TransactionType.DEBIT) {
            return;
        }
        if (command.amount().compareTo(properties.debitMaxAmount()) > 0) {
            throw new BusinessRuleViolationException(
                    CODE,
                    "DEBIT amount " + command.amount().toPlainString()
                            + " exceeds the maximum of " + properties.debitMaxAmount().toPlainString()
            );
        }
    }
}
