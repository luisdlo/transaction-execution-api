package com.spin.transactions.service.provider.dto;

import com.spin.transactions.model.Transaction;

import java.math.BigDecimal;

/**
 * Wire contract sent to the provider on POST /provider/v1/execute.
 * Four fields, exactly the ones in the provider spec — no auditing metadata leaks.
 */
public record ProviderExecuteRequest(
        String accountId,
        String type,
        BigDecimal amount,
        String currency
) {

    public static ProviderExecuteRequest from(Transaction transaction) {
        return new ProviderExecuteRequest(
                transaction.accountId(),
                transaction.type().name(),
                transaction.amount(),
                transaction.currency()
        );
    }
}
