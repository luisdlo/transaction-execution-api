package com.spin.transactions.controller;

/**
 * HTTP header names custom to this API. Standard headers (Location, Content-Type,
 * etc.) already live as constants in {@link org.springframework.http.HttpHeaders};
 * only put here headers this service defines itself, so grep on a header name lands
 * in a single place.
 */
public final class ApiHeaders {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private ApiHeaders() {
    }
}
