package com.example.fixclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class FixClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(FixClientApplication.class, args);
    }

    /**
     * The bean name is what a session's {@code fix-application-instance-id} names.
     */
    @Bean("order-entry")
    public OrderEntryApplication orderEntryApplication(ExecutionReportHandler executionReportHandler) {
        return new OrderEntryApplication(executionReportHandler);
    }
}
