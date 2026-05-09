package com.yourbank.payments.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WirePaymentEntityTest {

    @Test
    void from_mapsAllFieldsCorrectly() {
        var transactionId = UUID.randomUUID();
        var amount        = new BigDecimal("12345.67");
        var valueDate     = Instant.parse("2026-05-09T14:30:00Z");

        var debit  = new AccountInfo("12345678901", "021000021", null, null, "Alice Corp", null);
        var credit = new AccountInfo("98765432101", "011000138", null, null, "Bob Inc",   null);
        var data   = new TransactionData(
            transactionId, amount, "USD", valueDate, PaymentType.FEDNOW, "ref memo", "SALA");
        var event  = new WirePaymentEvent(
            new TransactionWrapper(debit, credit, data),
            new PartyInfo("u-99", "c-42"));

        WirePaymentEntity entity = WirePaymentEntity.from(event);

        assertThat(entity.transactionId()).isEqualTo(transactionId);
        assertThat(entity.amount()).isEqualByComparingTo(amount);
        assertThat(entity.currency()).isEqualTo("USD");
        assertThat(entity.valueDate()).isEqualTo(valueDate);
        assertThat(entity.paymentType()).isEqualTo("FEDNOW");
        assertThat(entity.debitAccountNumber()).isEqualTo("12345678901");
        assertThat(entity.debitRoutingNumber()).isEqualTo("021000021");
        assertThat(entity.debitHolderName()).isEqualTo("Alice Corp");
        assertThat(entity.creditAccountNumber()).isEqualTo("98765432101");
        assertThat(entity.creditRoutingNumber()).isEqualTo("011000138");
        assertThat(entity.creditHolderName()).isEqualTo("Bob Inc");
        assertThat(entity.userId()).isEqualTo("u-99");
        assertThat(entity.companyId()).isEqualTo("c-42");
        assertThat(entity.memo()).isEqualTo("ref memo");
        assertThat(entity.purposeCode()).isEqualTo("SALA");
        assertThat(entity.status()).isEqualTo("RECEIVED");
        assertThat(entity.schemaVersion()).isEqualTo("v1");
    }

    @Test
    void from_generatesUniquePaymentIdOnEachCall() {
        var event = TestPaymentFactory.makeEvent();

        var entity1 = WirePaymentEntity.from(event);
        var entity2 = WirePaymentEntity.from(event);

        assertThat(entity1.paymentId()).isNotNull();
        assertThat(entity2.paymentId()).isNotNull();
        assertThat(entity1.paymentId()).isNotEqualTo(entity2.paymentId());
    }

    @Test
    void from_propagatesNullOptionalFields() {
        var debit  = new AccountInfo("12345678901", "021000021", null, null, "Alice Corp", null);
        var credit = new AccountInfo("98765432101", "011000138", null, null, "Bob Inc",   null);
        var data   = new TransactionData(
            UUID.randomUUID(), new BigDecimal("100"), "USD",
            Instant.now(), PaymentType.ACH,
            null,  // memo — absent
            null); // purposeCode — absent
        var event = new WirePaymentEvent(
            new TransactionWrapper(debit, credit, data),
            new PartyInfo("u1", "c1"));

        WirePaymentEntity entity = WirePaymentEntity.from(event);

        assertThat(entity.memo()).isNull();
        assertThat(entity.purposeCode()).isNull();
    }

    @Test
    void from_statusIsAlwaysReceived() {
        var entity = WirePaymentEntity.from(TestPaymentFactory.makeEvent());
        assertThat(entity.status()).isEqualTo("RECEIVED");
    }

    @Test
    void from_schemaVersionIsAlwaysV1() {
        var entity = WirePaymentEntity.from(TestPaymentFactory.makeEvent());
        assertThat(entity.schemaVersion()).isEqualTo("v1");
    }
}
