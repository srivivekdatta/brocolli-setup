package com.yourbank.payments.consumer;

import com.yourbank.payments.dlq.DlqRouter;
import com.yourbank.payments.exception.MalformedPayloadException;
import com.yourbank.payments.exception.PaymentProcessingException;
import com.yourbank.payments.exception.SchemaValidationException;
import com.yourbank.payments.model.WirePaymentEntity;
import com.yourbank.payments.model.WirePaymentEvent;
import com.yourbank.payments.repository.WirePaymentRepository;
import com.yourbank.payments.validator.WirePaymentSchemaValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Batch Kafka listener for wire payments.
 *
 * Flow per batch:
 *   1. For each record: validate schema → deserialize → map to entity
 *      Bad records → DLQ immediately, do not fail the batch
 *   2. Batch insert all valid entities to Oracle in one round trip
 *   3. Commit Kafka offset only AFTER Oracle insert succeeds
 *
 * Why batch over per-record:
 *   400 TPS × per-record = 400 Oracle round trips/sec
 *   400 TPS ÷ 100 batch  =   4 Oracle round trips/sec
 *
 * Concurrency: each record processed async via paymentExecutor,
 * CompletableFuture.allOf waits for all before inserting.
 */
@Component
public class WirePaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(WirePaymentConsumer.class);

    private final WirePaymentSchemaValidator validator;
    private final WirePaymentRepository      repository;
    private final DlqRouter                  dlqRouter;
    private final Executor                   paymentExecutor;
    private final Timer                      batchTimer;

    public WirePaymentConsumer(
            WirePaymentSchemaValidator validator,
            WirePaymentRepository repository,
            DlqRouter dlqRouter,
            Executor paymentExecutor,
            MeterRegistry meterRegistry) {
        this.validator       = validator;
        this.repository      = repository;
        this.dlqRouter       = dlqRouter;
        this.paymentExecutor = paymentExecutor;
        this.batchTimer = Timer.builder("wire.payment.batch.duration")
            .description("Time to process a full Kafka batch end-to-end")
            .register(meterRegistry);
    }

    @KafkaListener(
        topics                = "${spring.kafka.consumer.topics:wire.payments.inbound}",
        containerFactory      = "wirePaymentListenerFactory"
    )
    public void consume(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.debug("Received batch of {} records", records.size());

        batchTimer.record(() -> {
            List<WirePaymentEntity> validEntities = processBatch(records);

            if (!validEntities.isEmpty()) {
                repository.insertBatch(validEntities);
                log.info("Inserted batch | size={} valid={}", records.size(), validEntities.size());
            }

            // Offset commits ONLY after Oracle insert — at-least-once guarantee
            ack.acknowledge();
        });
    }

    // ── Process batch concurrently ────────────────────────────────────────────
    private List<WirePaymentEntity> processBatch(List<ConsumerRecord<String, String>> records) {
        // Thread-safe accumulator for valid entities
        List<WirePaymentEntity> entities = new ArrayList<>();

        List<CompletableFuture<Void>> futures = records.stream()
            .map(record -> CompletableFuture.runAsync(
                () -> processRecord(record, entities), paymentExecutor))
            .toList();

        // Wait for all records in batch before proceeding to Oracle insert
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .exceptionally(ex -> {
                log.error("Unexpected error during batch processing", ex);
                return null;
            })
            .join();

        return entities;
    }

    // ── Process single record ─────────────────────────────────────────────────
    private void processRecord(
            ConsumerRecord<String, String> record,
            List<WirePaymentEntity> accumulator) {
        try {
            WirePaymentEvent event  = validator.validateAndDeserialize(record.value());
            WirePaymentEntity entity = WirePaymentEntity.from(event);

            synchronized (accumulator) {
                accumulator.add(entity);
            }

        } catch (SchemaValidationException | MalformedPayloadException e) {
            // Bad message — route to DLQ, do not fail the batch
            log.warn("Invalid payload | topic={} offset={} reason={}",
                record.topic(), record.offset(), e.getMessage());
            dlqRouter.send(record, e);

        } catch (Exception e) {
            // Unexpected error — route to DLQ, log at ERROR
            log.error("Unexpected error processing record | offset={}", record.offset(), e);
            dlqRouter.send(record, e);
        }
    }
}
