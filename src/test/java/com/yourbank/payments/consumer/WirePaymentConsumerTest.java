package com.yourbank.payments.consumer;

import com.yourbank.payments.dlq.DlqRouter;
import com.yourbank.payments.exception.MalformedPayloadException;
import com.yourbank.payments.exception.SchemaValidationException;
import com.yourbank.payments.model.TestPaymentFactory;
import com.yourbank.payments.model.WirePaymentEntity;
import com.yourbank.payments.repository.WirePaymentRepository;
import com.yourbank.payments.validator.WirePaymentSchemaValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WirePaymentConsumerTest {

    @Mock WirePaymentSchemaValidator validator;
    @Mock WirePaymentRepository       repository;
    @Mock DlqRouter                   dlqRouter;
    @Mock Acknowledgment              ack;

    private WirePaymentConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new WirePaymentConsumer(
            validator, repository, dlqRouter,
            Executors.newFixedThreadPool(4),
            new SimpleMeterRegistry());
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void consume_insertsValidEntitiesAndAcknowledges() {
        var txId   = UUID.randomUUID();
        var event  = TestPaymentFactory.makeEvent(txId);
        var record = kafkaRecord("valid-payload");

        when(validator.validateAndDeserialize("valid-payload")).thenReturn(event);

        consumer.consume(List.of(record), ack);

        verify(repository).insertBatch(argThat(entities ->
            entities.size() == 1 &&
            entities.get(0).transactionId().equals(txId)));
        verify(ack).acknowledge();
        verifyNoInteractions(dlqRouter);
    }

    @Test
    void consume_processesBatchOfMultipleValidRecords() {
        var event1 = TestPaymentFactory.makeEvent(UUID.randomUUID());
        var event2 = TestPaymentFactory.makeEvent(UUID.randomUUID());
        var record1 = kafkaRecord("payload-1");
        var record2 = kafkaRecord("payload-2");

        when(validator.validateAndDeserialize("payload-1")).thenReturn(event1);
        when(validator.validateAndDeserialize("payload-2")).thenReturn(event2);

        consumer.consume(List.of(record1, record2), ack);

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        verify(ack).acknowledge();
    }

    // ── Error isolation — bad records do not fail the batch ───────────────────

    @Test
    void consume_routesSchemaInvalidRecordToDlqAndStillInsertsBatchRemainder() {
        var goodEvent  = TestPaymentFactory.makeEvent(UUID.randomUUID());
        var goodRecord = kafkaRecord("good-payload");
        var badRecord  = kafkaRecord("bad-payload");

        when(validator.validateAndDeserialize("good-payload")).thenReturn(goodEvent);
        when(validator.validateAndDeserialize("bad-payload"))
            .thenThrow(new SchemaValidationException(Set.of()));

        consumer.consume(List.of(goodRecord, badRecord), ack);

        verify(repository).insertBatch(argThat(entities -> entities.size() == 1));
        verify(dlqRouter).send(eq(badRecord), any(SchemaValidationException.class));
        verify(ack).acknowledge();
    }

    @Test
    void consume_routesMalformedRecordToDlqAndStillInsertsBatchRemainder() {
        var goodEvent  = TestPaymentFactory.makeEvent(UUID.randomUUID());
        var goodRecord = kafkaRecord("good-payload");
        var badRecord  = kafkaRecord("malformed-payload");

        when(validator.validateAndDeserialize("good-payload")).thenReturn(goodEvent);
        when(validator.validateAndDeserialize("malformed-payload"))
            .thenThrow(new MalformedPayloadException("bad json", new RuntimeException()));

        consumer.consume(List.of(goodRecord, badRecord), ack);

        verify(repository).insertBatch(argThat(entities -> entities.size() == 1));
        verify(dlqRouter).send(eq(badRecord), any(MalformedPayloadException.class));
        verify(ack).acknowledge();
    }

    @Test
    void consume_routesUnexpectedExceptionToDlq() {
        var record = kafkaRecord("payload");
        when(validator.validateAndDeserialize("payload"))
            .thenThrow(new RuntimeException("unexpected"));

        consumer.consume(List.of(record), ack);

        verify(dlqRouter).send(eq(record), any(RuntimeException.class));
        verify(repository, never()).insertBatch(any());
        verify(ack).acknowledge();
    }

    @Test
    void consume_allInvalidBatch_doesNotCallInsertAndStillAcknowledges() {
        var record1 = kafkaRecord("bad-1");
        var record2 = kafkaRecord("bad-2");

        when(validator.validateAndDeserialize(any()))
            .thenThrow(new SchemaValidationException(Set.of()));

        consumer.consume(List.of(record1, record2), ack);

        verify(repository, never()).insertBatch(any());
        verify(dlqRouter, times(2)).send(any(), any());
        verify(ack).acknowledge();
    }

    // ── ACK ordering — offset committed only after Oracle insert ─────────────

    @Test
    void consume_doesNotAcknowledge_whenRepositoryThrows() {
        var record = kafkaRecord("payload");
        when(validator.validateAndDeserialize("payload"))
            .thenReturn(TestPaymentFactory.makeEvent());
        doThrow(new RuntimeException("Oracle down")).when(repository).insertBatch(any());

        assertThatThrownBy(() -> consumer.consume(List.of(record), ack))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Oracle down");

        verify(ack, never()).acknowledge();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ConsumerRecord<String, String> kafkaRecord(String value) {
        return new ConsumerRecord<>("wire.payments.inbound", 0, 0L, "key", value);
    }
}
