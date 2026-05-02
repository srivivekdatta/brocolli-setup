package com.yourbank.payments.exception;

public class PaymentProcessingException extends RuntimeException {
    public PaymentProcessingException(String paymentKey, Throwable cause) {
        super("Payment processing failed for key: " + paymentKey, cause);
    }
}
