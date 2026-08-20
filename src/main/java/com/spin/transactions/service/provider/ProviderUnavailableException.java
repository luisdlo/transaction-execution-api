package com.spin.transactions.service.provider;

/**
 * The provider cannot handle the request right now: 5xx, 429, connect timeout,
 * unreachable host. The request either never left our process or the provider is
 * explicitly asking us to back off. Safe to retry — the transaction has not been
 * committed on their side.
 */
public class ProviderUnavailableException extends RuntimeException {

    private final String code;

    public ProviderUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ProviderUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
