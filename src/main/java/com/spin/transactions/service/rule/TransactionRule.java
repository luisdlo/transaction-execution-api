package com.spin.transactions.service.rule;

import com.spin.transactions.model.TransactionCommand;

@FunctionalInterface
public interface TransactionRule {

    void validate(TransactionCommand command);
}
