package com.spin.transactions.exception;

import java.util.UUID;

public class ConcurrentTransactionUpdateException extends RuntimeException {

    private final UUID id;

    public ConcurrentTransactionUpdateException(UUID id) {
        super("transaction " + id + " was not in PENDING state");
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
