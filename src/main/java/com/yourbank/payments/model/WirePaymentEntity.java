package com.yourbank.payments.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Flat DB projection — maps 1:1 to wire_payments table columns.
 * Deliberately flat — no nested objects — for clean JDBC mapping.
 * Address stored separately in wire_payment_addresses.
 */
public record WirePaymentEntity(
    UUID       paymentId,
    UUID       transactionId,
    BigDecimal amount,
    String     currency,
    Instant    valueDate,
    String     paymentType,
    String     debitAccountNumber,
    String     debitRoutingNumber,
    String     debitHolderName,
    String     creditAccountNumber,
    String     creditRoutingNumber,
    String     creditHolderName,
    String     userId,
    String     companyId,
    String     memo,
    String     purposeCode,
    String     status,
    String     schemaVersion
) {
    /** Map from domain event to DB entity */
    public static WirePaymentEntity from(WirePaymentEvent event) {
        var txn   = event.transaction();
        var data  = txn.transactionData();
        var party = event.party();
        return new WirePaymentEntity(
            UUID.randomUUID(),
            data.transactionId(),
            data.amount(),
            data.currency(),
            data.valueDate(),
            data.paymentType().name(),
            txn.debitAccount().accountNumber(),
            txn.debitAccount().routingNumber(),
            txn.debitAccount().accountHolderName(),
            txn.creditAccount().accountNumber(),
            txn.creditAccount().routingNumber(),
            txn.creditAccount().accountHolderName(),
            party.userId(),
            party.companyId(),
            data.memo(),
            data.purposeCode(),
            "RECEIVED",
            "v1"
        );
    }
}
