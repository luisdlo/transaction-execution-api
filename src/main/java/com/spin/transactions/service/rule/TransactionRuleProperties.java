package com.spin.transactions.service.rule;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "transaction.rules")
public record TransactionRuleProperties(
        BigDecimal minimumAmount,
        BigDecimal debitMaxAmount,
        String supportedCurrency
) {
}
