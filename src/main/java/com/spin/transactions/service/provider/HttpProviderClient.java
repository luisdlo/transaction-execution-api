package com.spin.transactions.service.provider;

import com.spin.transactions.config.ProviderRestClientConfig;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.service.provider.dto.ProviderErrorResponse;
import com.spin.transactions.service.provider.dto.ProviderExecuteRequest;
import com.spin.transactions.service.provider.dto.ProviderExecuteResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * HTTP implementation of {@link ProviderClient}.
 *
 * <p>Uses {@code RestClient.exchange(...)} instead of {@code retrieve()} so every
 * HTTP status code goes through a single dispatch method and every error body is
 * parsed defensively. {@code retrieve()} would force us to configure a
 * {@code ResponseErrorHandler} and split success/error paths across two callbacks;
 * one exchange lambda keeps the "wire → domain" translation readable in one place.
 */
@Component
public class HttpProviderClient implements ProviderClient {

    private static final String EXECUTE_PATH = "/provider/v1/execute";
    private static final String APPROVED_STATUS = "APPROVED";
    private static final String FALLBACK_ERROR_CODE = "PROVIDER_ERROR";

    private final RestClient restClient;

    public HttpProviderClient(@Qualifier(ProviderRestClientConfig.PROVIDER_REST_CLIENT_BEAN)
                              RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * The retry list is a whitelist ({@code includes}), not a blacklist: adding a
     * new domain exception must never accidentally opt it into being retried.
     * Only {@link ProviderUnavailableException} is safe to retry — a rejection is a
     * business outcome, and an unknown-state signal MUST NOT be retried because
     * the request has already left the client and the charge may have gone through.
     *
     * <p>{@code maxRetries} is Spring's semantic: additional attempts <em>after</em>
     * the initial one. The property {@code provider.retry.max-retries=2} therefore
     * produces 3 total requests.
     */
    @Override
    @Retryable(
            includes = ProviderUnavailableException.class,
            maxRetriesString = "${provider.retry.max-retries}",
            delayString = "${provider.retry.delay}",
            multiplierString = "${provider.retry.multiplier}",
            jitterString = "${provider.retry.jitter}"
    )
    public ProviderExecution execute(Transaction transaction) {
        try {
            return restClient.post()
                    .uri(EXECUTE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ProviderExecuteRequest.from(transaction))
                    .exchange(this::dispatch);
        } catch (ResourceAccessException e) {
            throw translateIoException(e);
        }
    }

    // Signature carries `throws IOException` to match the ExchangeFunction contract,
    // even though bodyTo(...) in this Spring version only throws RuntimeException.
    private ProviderExecution dispatch(HttpRequest request,
                                      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();

        // 429 must be checked before the 4xx branch: it IS a 4xx, but semantically it is
        // "try again later", not a business rejection. Falling into the 4xx branch would
        // turn it into a ProviderRejectedException that never retries.
        if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value() || status.is5xxServerError()) {
            ProviderErrorResponse error = safeParseError(response);
            throw new ProviderUnavailableException(codeOrFallback(error), messageOrFallback(error));
        }
        if (status.is2xxSuccessful()) {
            return handleSuccess(response);
        }
        if (status.is4xxClientError()) {
            // A 4xx is a business rejection (INSUFFICIENT_FUNDS, ACCOUNT_BLOCKED, ...).
            // A 4xx is deterministic: retrying it cannot produce a different response.
            ProviderErrorResponse error = safeParseError(response);
            throw new ProviderRejectedException(codeOrFallback(error), messageOrFallback(error));
        }
        // 1xx and 3xx on an execute POST are protocol violations; treat as ambiguous.
        throw new ProviderUnknownStateException("Unexpected HTTP status " + status.value());
    }

    private ProviderExecution handleSuccess(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        ProviderExecuteResponse body;
        try {
            body = response.bodyTo(ProviderExecuteResponse.class);
        } catch (RuntimeException e) {
            // A 200 whose body we cannot read is the classic "did the charge happen?"
            // situation. Refuse to guess: mark unknown so the service can reconcile.
            throw new ProviderUnknownStateException(
                    "Unreadable 200 response from provider; outcome unknown", e);
        }
        if (body == null) {
            throw new ProviderUnknownStateException(
                    "200 response with empty body; outcome unknown");
        }
        // Defensive: the provider CAN reject with HTTP 200 (see spec: the error shape
        // is documented on 4xx/5xx but nothing prevents a status="REJECTED" on 200).
        // Never assume success from the HTTP status alone.
        if (!APPROVED_STATUS.equals(body.status())) {
            throw new ProviderRejectedException(
                    FALLBACK_ERROR_CODE, "Provider returned non-APPROVED status: " + body.status());
        }
        // Approved-but-incomplete: the provider claims success but omitted a field the
        // domain requires. Do not let it explode inside the ProviderExecution constructor
        // as a raw NPE — surface it as an ambiguous outcome so the service can reconcile.
        if (body.transactionId() == null || body.transactionId().isBlank()
                || body.balance() == null || body.executedAt() == null) {
            throw new ProviderUnknownStateException(
                    "200 APPROVED response with missing fields; outcome unknown");
        }
        return new ProviderExecution(body.transactionId(), body.balance(), body.executedAt());
    }

    private ProviderErrorResponse safeParseError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(ProviderErrorResponse.class);
        } catch (RuntimeException ignored) {
            // Empty, non-JSON or malformed error body — fall back to status-only
            // mapping instead of surfacing a parser exception the caller can't act on.
            return null;
        }
    }

    private static String codeOrFallback(ProviderErrorResponse error) {
        if (error == null || error.code() == null || error.code().isBlank()) {
            return FALLBACK_ERROR_CODE;
        }
        return error.code();
    }

    private static String messageOrFallback(ProviderErrorResponse error) {
        if (error == null || error.message() == null || error.message().isBlank()) {
            return "Provider error";
        }
        return error.message();
    }

    /**
     * Root-cause dispatch of I/O failures. This is the most important decision in
     * this class:
     * <ul>
     *   <li><b>Connect timeout / connection refused</b> — the request never left the
     *       client. Safe to retry: no side-effect on the provider. Mapped to
     *       {@link ProviderUnavailableException}.</li>
     *   <li><b>Read/socket timeout</b> — the request went out but the response never
     *       came back. The provider MAY have executed the charge. Retrying would
     *       duplicate real money. Mapped to {@link ProviderUnknownStateException};
     *       the service marks the transaction FAILED for manual reconciliation.</li>
     * </ul>
     */
    private RuntimeException translateIoException(ResourceAccessException e) {
        // Walk the cause chain: RestClient wraps the underlying I/O failure inside a
        // ResourceAccessException, and the JDK HttpClient itself sometimes wraps its
        // timeout/connect exceptions one more level deep (e.g. inside IOException).
        // The 10-level guard is a paranoia bound against a pathological cause cycle.
        Throwable cause = e.getCause();
        for (int depth = 0; cause != null && depth < 10; depth++) {
            // Order matters: HttpConnectTimeoutException extends HttpTimeoutException.
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return new ProviderUnavailableException(
                        "PROVIDER_UNREACHABLE", "Could not connect to provider", e);
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new ProviderUnknownStateException(
                        "Read timeout waiting for provider response; outcome unknown", e);
            }
            cause = cause.getCause();
        }
        // Any other I/O failure: err on the side of unknown. Retrying a request whose
        // fate we do not understand is exactly what causes double charges.
        return new ProviderUnknownStateException(
                "Unexpected I/O failure talking to provider; outcome unknown", e);
    }
}
