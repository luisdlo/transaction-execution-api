package com.spin.transactions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TransactionExecutionApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionExecutionApiApplication.class, args);
    }

}
