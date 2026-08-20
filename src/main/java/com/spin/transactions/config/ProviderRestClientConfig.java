package com.spin.transactions.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

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
 * for read-timeout. {@link com.spin.transactions.service.provider.HttpProviderClient}
 * relies on that distinction to decide whether an ambiguous outcome is safe to retry.
 *
 * <p>{@link EnableResilientMethods} enables Spring Framework 7 native processing of
 * {@code @Retryable}, which lives on {@code HttpProviderClient#execute(...)}.
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
@EnableResilientMethods(proxyTargetClass = true)
public class ProviderRestClientConfig {

    public static final String PROVIDER_REST_CLIENT_BEAN = "providerRestClient";

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
