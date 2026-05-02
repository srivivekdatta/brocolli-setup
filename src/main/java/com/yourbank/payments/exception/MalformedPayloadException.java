package com.yourbank.payments.exception;

public class MalformedPayloadException extends RuntimeException {
    public MalformedPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
