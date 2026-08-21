package com.spin.transactions.exception;

/**
 * The provider explicitly rejected the transaction (known outcome). The charge did
 * not happen. This is a business signal, not a failure of the provider: fondos
 * insuficientes, cuenta bloqueada, etc. Must never trigger a retry.
 */
public class ProviderRejectedException extends RuntimeException {

    private final String code;

    public ProviderRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
