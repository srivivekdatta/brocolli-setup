# wire-payment-service

Spring Boot 3.3 service consuming wire payments from Kafka, validating against ISO 20022-aligned JSON Schema, and persisting to Oracle 19c.

## Stack

| Layer | Technology |
|---|---|
| Runtime | JDK 17 |
| Framework | Spring Boot 3.3 |
| Messaging | Apache Kafka (batch listener, manual ack) |
| Schema validation | JSON Schema Draft-7 (networknt) |
| Database | Oracle 19c + UCP connection pool |
| DB access | JdbcClient + NamedParameterJdbcTemplate |
| Resilience | Resilience4j (CB + retry + bulkhead) |
| Observability | Splunk OTEL (micrometer-otlp + tracing) |
| Deploy | OpenShift — 5 pods, 8 CPU / 5Gi each |

## Project structure

```
src/main/java/com/yourbank/payments/
├── WirePaymentServiceApplication.java
├── config/
│   ├── KafkaConfig.java          # Kafka consumer factory + executor sizing
│   └── JacksonConfig.java        # ObjectMapper with JavaTimeModule
├── consumer/
│   └── WirePaymentConsumer.java  # Batch Kafka listener
├── model/
│   ├── WirePaymentEvent.java     # Kafka payload POJOs (records)
│   └── WirePaymentEntity.java    # Flat Oracle projection
├── validator/
│   └── WirePaymentSchemaValidator.java
├── repository/
│   └── WirePaymentRepository.java
├── dlq/
│   └── DlqRouter.java
└── exception/
    ├── SchemaValidationException.java
    ├── MalformedPayloadException.java
    └── PaymentProcessingException.java

src/main/resources/
├── application.yml
└── schemas/
    └── wire-payment-v1.json      # ISO 20022 aligned JSON Schema

db/migration/
└── V1__create_wire_payments.sql  # Oracle DDL + indexes

k8s/
└── deployment.yaml               # OpenShift Deployment + HPA + ConfigMap + Secret
```

## Key design decisions

**Batch listener over per-record**
At 400 TPS, per-record = 400 Oracle round trips/sec. Batch of 100 = 4 round trips/sec. Same throughput, 98% fewer DB connections consumed.

**Manual offset commit**
Kafka offset is committed only AFTER Oracle insert succeeds. If the pod dies mid-batch, Kafka replays — idempotency on `transaction_id` unique constraint prevents double-inserts.

**Pre-filter duplicates before batchUpdate**
Oracle aborts the entire batch on a unique constraint violation. Pre-filtering with `existsByTransactionId` means one duplicate doesn't kill 99 valid records.

**FOR UPDATE SKIP LOCKED**
Multi-pod safe polling for downstream processing. Pod A locks rows 1–10, Pod B skips to rows 11–20. No deadlock, no double-processing.

**Separate address table**
Keeps `wire_payments` narrow — Oracle buffer cache holds more rows per block. Addresses are queried for compliance/fraud, not on every payment transition.

**JVM sizing (5Gi pod)**
- `-Xms1g -Xmx3g` — leaves ~2Gi for OS, OTEL agent, thread stacks, metaspace
- G1GC with 200ms max pause target — predictable latency over max throughput

## Running locally

```bash
# Requires Docker for Testcontainers
./gradlew test

# Run with local Kafka + Oracle
export DB_URL=jdbc:oracle:thin:@//localhost:1521/XEPDB1
export DB_USERNAME=payments
export DB_PASSWORD=secret
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./gradlew bootRun
```

## Environment variables

| Variable | Description |
|---|---|
| `DB_URL` | Oracle JDBC URL |
| `DB_USERNAME` | Oracle username |
| `DB_PASSWORD` | Oracle password |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker list |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Splunk OTEL collector endpoint |

## Kafka topics

| Topic | Purpose |
|---|---|
| `wire.payments.inbound` | Inbound wire payment events (consumed) |
| `wire.payments.dlq` | Dead letter — schema failures, malformed payloads |
