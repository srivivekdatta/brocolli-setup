package com.yourbank.payments.dlq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqRouterTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;

    private DlqRouter dlqRouter;

    @BeforeEach
    void setUp() {
        dlqRouter = new DlqRouter(kafkaTemplate, "wire.payments.dlq");
        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test
    void send_routesMessageToDlqTopic() {
        dlqRouter.send(sourceRecord("payload"), new RuntimeException("oops"));

        var captor = captureProducerRecord();
        assertThat(captor.topic()).isEqualTo("wire.payments.dlq");
    }

    @Test
    void send_preservesOriginalKeyAndValue() {
        dlqRouter.send(sourceRecord("the-payload"), new RuntimeException("oops"));

        var sent = captureProducerRecord();
        assertThat(sent.key()).isEqualTo("original-key");
        assertThat(sent.value()).isEqualTo("the-payload");
    }

    // ── Headers ───────────────────────────────────────────────────────────────

    @Test
    void send_addsOriginalTopicHeader() {
        dlqRouter.send(sourceRecord("p"), new RuntimeException("e"));
        assertHeader(captureProducerRecord().headers(), "x-original-topic", "wire.payments.inbound");
    }

    @Test
    void send_addsOriginalPartitionHeader() {
        dlqRouter.send(sourceRecord("p"), new RuntimeException("e"));
        assertHeader(captureProducerRecord().headers(), "x-original-partition", "0");
    }

    @Test
    void send_addsOriginalOffsetHeader() {
        dlqRouter.send(sourceRecord("p"), new RuntimeException("e"));
        assertHeader(captureProducerRecord().headers(), "x-original-offset", "42");
    }

    @Test
    void send_addsFailureReasonHeader_withExceptionSimpleName() {
        dlqRouter.send(sourceRecord("p"), new IllegalArgumentException("bad"));
        assertHeader(captureProducerRecord().headers(), "x-failure-reason", "IllegalArgumentException");
    }

    @Test
    void send_addsFailureMessageHeader_withExceptionMessage() {
        dlqRouter.send(sourceRecord("p"), new RuntimeException("something broke"));
        assertHeader(captureProducerRecord().headers(), "x-failure-message", "something broke");
    }

    @Test
    void send_truncatesLongFailureMessage() {
        var longMessage = "x".repeat(600);
        dlqRouter.send(sourceRecord("p"), new RuntimeException(longMessage));

        var headers   = captureProducerRecord().headers();
        var truncated = headerValue(headers, "x-failure-message");
        assertThat(truncated).hasSize(500);
    }

    @Test
    void send_usesUnknown_whenExceptionMessageIsNull() {
        dlqRouter.send(sourceRecord("p"), new RuntimeException((String) null));
        assertHeader(captureProducerRecord().headers(), "x-failure-message", "unknown");
    }

    // ── DLQ send failure ──────────────────────────────────────────────────────

    @Test
    void send_doesNotThrow_whenKafkaTemplateSendFails() {
        var failedFuture = new CompletableFuture<SendResult<String, String>>();
        failedFuture.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        // No exception should propagate to the caller — DLQ failure is logged only
        dlqRouter.send(sourceRecord("p"), new RuntimeException("original error"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ConsumerRecord<String, String> sourceRecord(String value) {
        return new ConsumerRecord<>("wire.payments.inbound", 0, 42L, "original-key", value);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captureProducerRecord() {
        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private void assertHeader(Headers headers, String key, String expectedValue) {
        assertThat(headerValue(headers, key))
            .as("header %s", key)
            .isEqualTo(expectedValue);
    }

    private String headerValue(Headers headers, String key) {
        var header = headers.lastHeader(key);
        assertThat(header).as("header '%s' must be present", key).isNotNull();
        return new String(header.value());
    }
}
