package com.spin.transactions.config;

import com.spin.transactions.exception.ProviderRejectedException;
import com.spin.transactions.exception.ProviderUnavailableException;
import com.spin.transactions.exception.ProviderUnknownStateException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Dedicated {@link RestClient} for the external provider, with its own timeouts.
 *
 * <p>We do not share the auto-configured {@code RestClient.Builder}: the provider is
 * one of many external clients we may end up talking to, and mixing global HTTP
 * defaults with per-provider timeouts is the classic way to end up with a shared
 * connection pool that saturates for the wrong reason.
 *
 * <p>The underlying transport is the JDK {@link HttpClient} on purpose:
 * unlike {@code SimpleClientHttpRequestFactory} (based on {@code HttpURLConnection}),
 * it throws distinct exception types for the two timeout kinds:
 * {@code HttpConnectTimeoutException} for connect-timeout and {@code HttpTimeoutException}
 * for read-timeout. {@link com.spin.transactions.service.impl.ProviderServiceImpl}
 * relies on that distinction to decide whether an ambiguous outcome is safe to retry.
 *
 * <p>{@link EnableResilientMethods} enables Spring Framework 7 native processing of
 * {@code @Retryable}, which lives on {@code ProviderServiceImpl#execute(...)}.
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
@EnableResilientMethods(proxyTargetClass = true)
public class ProviderRestClientConfig {

    public static final String PROVIDER_REST_CLIENT_BEAN = "providerRestClient";
    public static final String PROVIDER_CIRCUIT_BREAKER_BEAN = "providerCircuitBreaker";

    /**
     * Circuit breaker for the provider call. Counts only exceptions the provider's
     * infrastructure can trigger — {@link ProviderRejectedException} is a valid
     * business response and must NOT open the breaker (otherwise a wave of
     * insufficient-funds rejections would look like a provider outage).
     */
    @Bean(PROVIDER_CIRCUIT_BREAKER_BEAN)
    public CircuitBreaker providerCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(ProviderUnavailableException.class, ProviderUnknownStateException.class)
                .ignoreExceptions(ProviderRejectedException.class)
                .build();
        return CircuitBreaker.of("providerService", config);
    }

    @Bean(PROVIDER_REST_CLIENT_BEAN)
    public RestClient providerRestClient(ProviderProperties properties) {
        // Pin HTTP/1.1: the provider spec does not advertise HTTP/2, and the JDK
        // HttpClient's default h2-first negotiation surfaces spurious stream resets
        // as a generic IOException, which our error dispatch cannot tell apart from
        // an ambiguous outcome.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
