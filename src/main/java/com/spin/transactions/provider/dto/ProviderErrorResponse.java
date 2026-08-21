package com.spin.transactions.provider.dto;

/**
 * Error-path body from the provider on 4xx/5xx responses.
 * Any field may be absent — the parser has to tolerate empty and malformed bodies.
 */
public record ProviderErrorResponse(
        String status,
        String code,
        String message
) {
}
