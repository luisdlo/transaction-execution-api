package com.spin.transactions.repository;

import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionStatus;
import com.spin.transactions.model.TransactionType;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

final class TransactionRowMapper implements RowMapper<Transaction> {

    static final TransactionRowMapper INSTANCE = new TransactionRowMapper();

    private TransactionRowMapper() {
    }

    @Override
    public Transaction mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Transaction(
                rs.getObject("id", UUID.class),
                rs.getString("account_id"),
                TransactionType.valueOf(rs.getString("type")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("description"),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getString("provider_transaction_id"),
                rs.getBigDecimal("balance_after"),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", Instant.class),
                rs.getObject("updated_at", Instant.class)
        );
    }
}
