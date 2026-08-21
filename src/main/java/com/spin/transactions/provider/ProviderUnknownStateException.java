package com.spin.transactions.provider;

/**
 * The outcome of the call is ambiguous: a read timeout, or a 200 with a body we
 * cannot interpret. The request left the client but we do not know whether the
 * provider executed the charge.
 *
 * NEVER retried: reintentar duplicaría dinero real. The service marks the
 * transaction FAILED so an operator can reconcile against the provider.
 */
public class ProviderUnknownStateException extends RuntimeException {

    public ProviderUnknownStateException(String message) {
        super(message);
    }

    public ProviderUnknownStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
