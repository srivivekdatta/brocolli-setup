package com.yourbank.payments.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared factory for building test fixtures.
 * Must live in the model package to access the package-private inner records
 * (TransactionWrapper, AccountInfo, TransactionData, PartyInfo, PaymentType).
 */
public final class TestPaymentFactory {

    private TestPaymentFactory() {}

    public static WirePaymentEvent makeEvent() {
        return makeEvent(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
    }

    public static WirePaymentEvent makeEvent(UUID transactionId) {
        var debit = new AccountInfo(
            "12345678901", "021000021", "Bank A", null, "Alice Corp", null);
        var credit = new AccountInfo(
            "98765432101", "011000138", "Bank B", null, "Bob Inc", null);
        var data = new TransactionData(
            transactionId,
            new BigDecimal("5000.00"),
            "USD",
            Instant.parse("2026-05-09T14:30:00Z"),
            PaymentType.WIRE,
            "Test payment",
            "SUPP");
        return new WirePaymentEvent(
            new TransactionWrapper(debit, credit, data),
            new PartyInfo("user-123", "company-456"));
    }

    public static WirePaymentEntity makeEntity() {
        return makeEntity(UUID.randomUUID());
    }

    public static WirePaymentEntity makeEntity(UUID transactionId) {
        return new WirePaymentEntity(
            UUID.randomUUID(),
            transactionId,
            new BigDecimal("5000.00"),
            "USD",
            Instant.parse("2026-05-09T14:30:00Z"),
            "WIRE",
            "12345678901",
            "021000021",
            "Alice Corp",
            "98765432101",
            "011000138",
            "Bob Inc",
            "user-123",
            "company-456",
            "Test payment",
            "SUPP",
            "RECEIVED",
            "v1");
    }
}
