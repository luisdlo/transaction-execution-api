package com.spin.transactions;

import org.springframework.boot.SpringApplication;

public class TestTransactionExecutionApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(TransactionExecutionApiApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
