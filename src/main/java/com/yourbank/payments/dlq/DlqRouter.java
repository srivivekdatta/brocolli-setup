package com.yourbank.payments.dlq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Routes unprocessable messages to the DLQ topic.
 *
 * DLQ message includes:
 *  - Original payload (as-is)
 *  - Original Kafka headers
 *  - Failure reason header
 *  - Original topic/partition/offset for traceability
 */
@Component
public class DlqRouter {

    private static final Logger log = LoggerFactory.getLogger(DlqRouter.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dlqTopic;

    public DlqRouter(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.wire.dlq-topic}") String dlqTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic      = dlqTopic;
    }

    public void send(ConsumerRecord<String, String> original, Exception reason) {
        log.warn("Routing to DLQ | topic={} partition={} offset={} reason={}",
            original.topic(), original.partition(), original.offset(),
            reason.getMessage());

        var producerRecord = new org.apache.kafka.clients.producer.ProducerRecord<>(
            dlqTopic,
            null,
            original.key(),
            original.value(),
            original.headers()
        );

        // Enrich with failure metadata headers
        producerRecord.headers()
            .add("x-original-topic",
                original.topic().getBytes())
            .add("x-original-partition",
                String.valueOf(original.partition()).getBytes())
            .add("x-original-offset",
                String.valueOf(original.offset()).getBytes())
            .add("x-failure-reason",
                reason.getClass().getSimpleName().getBytes())
            .add("x-failure-message",
                truncate(reason.getMessage(), 500).getBytes());

        kafkaTemplate.send(producerRecord)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    // DLQ send failed — log at ERROR, do not swallow
                    log.error("CRITICAL: DLQ send failed for offset={} — message may be lost",
                        original.offset(), ex);
                }
            });
    }

    private String truncate(String value, int max) {
        if (value == null) return "unknown";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
