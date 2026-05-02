package com.yourbank.payments.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Kafka and executor configuration.
 *
 * Thread pool sizing rationale (per pod, 8 CPU cores):
 *   - I/O-bound workload (schema validation + DB insert)
 *   - Formula: 2 × CPU cores = 16 core threads
 *   - Max: 32 — headroom for bursts without OOM (5Gi limit)
 *   - Queue: 500 — backs up gracefully, gives time for DB to catch up
 *   - 5 pods × 16 core threads = 80 concurrent processing threads cluster-wide
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.max-poll-records:100}")
    private int maxPollRecords;

    @Value("${app.wire.executor.core-pool-size:16}")
    private int corePoolSize;

    @Value("${app.wire.executor.max-pool-size:32}")
    private int maxPoolSize;

    @Value("${app.wire.executor.queue-capacity:500}")
    private int queueCapacity;

    @Value("${app.wire.executor.thread-name-prefix:wire-proc-}")
    private String threadNamePrefix;

    // ── Consumer factory ──────────────────────────────────────────────────────
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,           groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,   maxPollRecords);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,    16384);   // 16KB
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,  500);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,    "read_committed");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 15_000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,  300_000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    // ── Batch listener container factory ─────────────────────────────────────
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> wirePaymentListenerFactory(
            ConsumerFactory<String, String> consumerFactory) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(3);  // 3 listener threads per pod × 5 pods = 15 total
        factory.getContainerProperties()
               .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // ── Payment processing executor ───────────────────────────────────────────
    @Bean
    public Executor paymentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        // CallerRunsPolicy: if queue is full, calling thread processes the task
        // — provides natural backpressure rather than dropping records
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
