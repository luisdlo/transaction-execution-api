package com.spin.transactions.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionExecutionOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Transaction Execution API")
                .version("v1")
                .description("Executes CREDIT/DEBIT transactions against an external provider, "
                        + "persists the outcome and exposes paginated read access. "
                        + "Terminal outcomes (EXECUTED, REJECTED, FAILED) are all returned with HTTP 201 — "
                        + "the caller inspects the `status` field to know which one."));
    }
}
