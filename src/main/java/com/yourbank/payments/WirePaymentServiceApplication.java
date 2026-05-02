package com.yourbank.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // for SKIP LOCKED poller
public class WirePaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WirePaymentServiceApplication.class, args);
    }
}
