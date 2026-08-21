package com.spin.transactions.provider;

import com.spin.transactions.provider.impl.HttpProviderClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.spin.transactions.config.ProviderRestClientConfig;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The test loads only the provider slice (RestClient config + HTTP client).
 * No {@code DataSource} or Flyway is required because the two @{@code Configuration}
 * classes passed via {@code classes = ...} do not enable auto-configuration.
 */
@SpringBootTest(
        classes = {ProviderRestClientConfig.class, HttpProviderClient.class},
        properties = {
                "provider.connect-timeout=500ms",
                "provider.read-timeout=500ms",
                "provider.retry.max-retries=2",
                "provider.retry.delay=10ms",
                "provider.retry.multiplier=1",
                "provider.retry.jitter=0ms"
        }
)
class HttpProviderClientTest {

    // The extension binds a dynamic port and manages start/stop across the test class.
    // @DynamicPropertySource registers a lazy Supplier, so getPort() is only invoked
    // when Spring resolves provider.base-url — by then WireMock's beforeAll has run.
    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointClientAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", () -> "http://localhost:" + wireMock.getPort());
    }

    @Autowired
    private ProviderClient providerClient;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
        // Otherwise 5xx/timeout tests would leave failure counters that trip the
        // breaker in a later test and short-circuit its HTTP call.
        circuitBreaker.reset();
    }

    private static final String EXECUTE_PATH = "/provider/v1/execute";

    private static Transaction pendingTransaction() {
        TransactionCommand command = new TransactionCommand(
                "acc-123456", TransactionType.CREDIT,
                new BigDecimal("1500.00"), "MXN",
                "Test transaction", null);
        return Transaction.pending(command, Instant.parse("2026-03-15T10:30:00Z"));
    }

    @Test
    @DisplayName("200 APPROVED maps the three fields and sends the four-field contract body")
    void returnsExecution_whenProviderApproves() {
        wireMock.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "txn-789",
                                  "status": "APPROVED",
                                  "balance": 5500.00,
                                  "executedAt": "2025-03-15T10:30:00Z"
                                }
                                """)));

        ProviderExecution execution = providerClient.execute(pendingTransaction());

        assertThat(execution.providerTransactionId()).isEqualTo("txn-789");
        assertThat(execution.balanceAfter()).isEqualByComparingTo("5500.00");
        assertThat(execution.executedAt()).isEqualTo(Instant.parse("2025-03-15T10:30:00Z"));

        // Exactly the four fields of the provider spec: no idempotency key,
        // no internal id, no timestamps leak from the domain model.
        wireMock.verify(postRequestedFor(urlEqualTo(EXECUTE_PATH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {
                          "accountId": "acc-123456",
                          "type": "CREDIT",
                          "amount": 1500.00,
                          "currency": "MXN"
                        }
                        """, true, false)));
    }

    @Test
    @DisplayName("400 INSUFFICIENT_FUNDS becomes ProviderRejectedException and IS NOT retried")
    void throwsRejected_andDoesNotRetry_on4xx() {
        // Retrying a business rejection is what causes double charges if the provider
        // ever changes its mind. The 4xx path must be single-shot.
        wireMock.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "REJECTED",
                                  "code": "INSUFFICIENT_FUNDS",
                                  "message": "Not enough balance"
                                }
                                """)));

        assertThatThrownBy(() -> providerClient.execute(pendingTransaction()))
                .isInstanceOfSatisfying(ProviderRejectedException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(ex.getMessage()).isEqualTo("Not enough balance");
                });

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(EXECUTE_PATH)));
    }

    @Test
    @DisplayName("503 becomes ProviderUnavailableException and is retried up to max-retries + 1")
    void throwsUnavailable_andRetriesThreeTimes_on5xx() {
        wireMock.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"REJECTED","code":"UNAVAILABLE","message":"Try later"}
                                """)));

        assertThatThrownBy(() -> providerClient.execute(pendingTransaction()))
                .isInstanceOf(ProviderUnavailableException.class);

        // max-retries=2 (Spring semantic: retries AFTER the initial attempt) -> 3 requests.
        wireMock.verify(exactly(3), postRequestedFor(urlEqualTo(EXECUTE_PATH)));
    }

    @Test
    @DisplayName("read timeout is ProviderUnknownStateException and MUST NOT be retried")
    void throwsUnknownState_andDoesNotRetry_onReadTimeout() {
        // Read-timeout means the request already left the client; the provider MAY
        // have executed the charge. Retrying would double it. The 2000ms delay is
        // well above the 500ms read-timeout, so a timeout is guaranteed.
        wireMock.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(2000)
                        .withBody("{}")));

        assertThatThrownBy(() -> providerClient.execute(pendingTransaction()))
                .isInstanceOf(ProviderUnknownStateException.class);

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(EXECUTE_PATH)));
    }

    @Test
    @DisplayName("200 with non-APPROVED status is a rejection, not a success")
    void throwsRejected_on200WithNonApprovedStatus() {
        wireMock.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "transactionId": "txn-999",
                                  "status": "REJECTED",
                                  "balance": 100.00,
                                  "executedAt": "2025-03-15T10:30:00Z"
                                }
                                """)));

        assertThatThrownBy(() -> providerClient.execute(pendingTransaction()))
                .isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    @DisplayName("non-JSON error body falls back to PROVIDER_ERROR without a parse exception")
    void handlesMalformedErrorBody() {
        wireMock.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>Bad Gateway</body></html>")));

        assertThatThrownBy(() -> providerClient.execute(pendingTransaction()))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("PROVIDER_ERROR"));
    }

    @Test
    @DisplayName("circuit breaker opens after the failure threshold and stops hitting the provider")
    void circuitBreaker_stopsHittingProvider_afterFailureThreshold() {
        // Config: 20-slot COUNT window, minimumNumberOfCalls=10, 50% failure rate.
        // Each execute() runs @Retryable = 3 attempts. 4 execute() calls with 503
        // saturate the window with failures somewhere in the middle of the 4th call,
        // after which the CB opens and subsequent attempts must NOT reach WireMock.
        wireMock.stubFor(post(urlEqualTo(EXECUTE_PATH))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"REJECTED","code":"UNAVAILABLE","message":"Try later"}
                                """)));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> providerClient.execute(pendingTransaction()))
                    .isInstanceOf(ProviderUnavailableException.class);
        }

        int hitsBefore = wireMock.findAll(postRequestedFor(urlEqualTo(EXECUTE_PATH))).size();

        assertThatThrownBy(() -> providerClient.execute(pendingTransaction()))
                .isInstanceOfSatisfying(ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("PROVIDER_CIRCUIT_OPEN"));

        int hitsAfter = wireMock.findAll(postRequestedFor(urlEqualTo(EXECUTE_PATH))).size();
        assertThat(hitsAfter)
                .as("circuit breaker must have short-circuited without touching the provider")
                .isEqualTo(hitsBefore);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
