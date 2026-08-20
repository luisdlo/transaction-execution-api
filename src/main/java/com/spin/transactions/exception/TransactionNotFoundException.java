package com.spin.transactions.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {

    private final UUID id;

    public TransactionNotFoundException(UUID id) {
        super("transaction " + id + " not found");
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
