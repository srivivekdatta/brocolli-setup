package com.yourbank.payments.repository;

import com.yourbank.payments.model.TestPaymentFactory;
import com.yourbank.payments.model.WirePaymentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for WirePaymentRepository against a real Oracle instance.
 * Requires Docker (container starts once for the whole class).
 */
@Testcontainers
class WirePaymentRepositoryIT {

    @Container
    static final OracleContainer ORACLE =
        new OracleContainer("gvenzl/oracle-free:23-slim");

    private WirePaymentRepository repository;
    private JdbcClient            jdbcClient;

    @BeforeEach
    void setUp() {
        DataSource ds = dataSource();
        jdbcClient = JdbcClient.create(ds);
        repository = new WirePaymentRepository(jdbcClient, new NamedParameterJdbcTemplate(ds));
        truncateTables();
    }

    // ── Schema migration — runs once on first test, then tables are reused ─────

    static {
        // Block until Oracle is ready, then create schema once.
        // @BeforeAll would require a static field; this static initialiser runs
        // after the @Container field is assigned (Testcontainers guarantees order).
    }

    // Spring's @Container ensures the container starts before any test method.
    // We run migration in setUp so it is idempotent after the first test.
    private static boolean migrationRan = false;

    private synchronized void ensureMigration() {
        if (!migrationRan) {
            var populator = new ResourceDatabasePopulator();
            populator.addScript(new FileSystemResource("db/migration/V1__create_wire_payments.sql"));
            populator.setSeparator(";");
            DatabasePopulatorUtils.execute(populator, dataSource());
            migrationRan = true;
        }
    }

    // ── insertBatch ───────────────────────────────────────────────────────────

    @Test
    void insertBatch_persistsNewPayments() {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();

        repository.insertBatch(List.of(entity));

        assertThat(repository.existsByTransactionId(entity.transactionId())).isTrue();
    }

    @Test
    void insertBatch_persistsMultiplePayments() {
        ensureMigration();
        var e1 = TestPaymentFactory.makeEntity(UUID.randomUUID());
        var e2 = TestPaymentFactory.makeEntity(UUID.randomUUID());

        repository.insertBatch(List.of(e1, e2));

        assertThat(repository.existsByTransactionId(e1.transactionId())).isTrue();
        assertThat(repository.existsByTransactionId(e2.transactionId())).isTrue();
    }

    @Test
    void insertBatch_deduplicatesOnTransactionId() {
        ensureMigration();
        var txId   = UUID.randomUUID();
        var first  = TestPaymentFactory.makeEntity(txId);
        var second = TestPaymentFactory.makeEntity(txId); // same transactionId, new paymentId

        // First insert succeeds; second is filtered as a duplicate
        repository.insertBatch(List.of(first));
        repository.insertBatch(List.of(second)); // must not throw

        long count = jdbcClient
            .sql("SELECT COUNT(1) FROM wire_payments WHERE transaction_id = :id")
            .param("id", txId.toString())
            .query(Long.class)
            .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void insertBatch_isNoOp_whenAllRecordsAreDuplicates() {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));

        // Inserting the same batch again must not throw
        repository.insertBatch(List.of(entity));

        long total = jdbcClient
            .sql("SELECT COUNT(1) FROM wire_payments")
            .query(Long.class)
            .single();
        assertThat(total).isEqualTo(1);
    }

    // ── existsByTransactionId ─────────────────────────────────────────────────

    @Test
    void existsByTransactionId_returnsTrueForInsertedRecord() {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));

        assertThat(repository.existsByTransactionId(entity.transactionId())).isTrue();
    }

    @Test
    void existsByTransactionId_returnsFalseForUnknownId() {
        ensureMigration();
        assertThat(repository.existsByTransactionId(UUID.randomUUID())).isFalse();
    }

    // ── pollForProcessing ─────────────────────────────────────────────────────

    @Test
    void pollForProcessing_returnsRecordsOlderThanOneSecond() throws Exception {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));
        makeRecordEligibleForPolling(entity.paymentId());

        List<WirePaymentEntity> results = repository.pollForProcessing(10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).transactionId()).isEqualTo(entity.transactionId());
    }

    @Test
    void pollForProcessing_doesNotReturnTooRecentRecords() {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));
        // Record just inserted — created_at = SYSTIMESTAMP, which is NOT older than 1s

        List<WirePaymentEntity> results = repository.pollForProcessing(10);

        assertThat(results).isEmpty();
    }

    @Test
    void pollForProcessing_respectsLimit() throws Exception {
        ensureMigration();
        var e1 = TestPaymentFactory.makeEntity(UUID.randomUUID());
        var e2 = TestPaymentFactory.makeEntity(UUID.randomUUID());
        var e3 = TestPaymentFactory.makeEntity(UUID.randomUUID());
        repository.insertBatch(List.of(e1, e2, e3));
        makeRecordEligibleForPolling(e1.paymentId());
        makeRecordEligibleForPolling(e2.paymentId());
        makeRecordEligibleForPolling(e3.paymentId());

        List<WirePaymentEntity> results = repository.pollForProcessing(2);

        assertThat(results).hasSize(2);
    }

    @Test
    void pollForProcessing_doesNotReturnAlreadyTransitionedRecords() throws Exception {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));
        makeRecordEligibleForPolling(entity.paymentId());
        repository.updateStatus(entity.paymentId(), "APPROVED", null);

        List<WirePaymentEntity> results = repository.pollForProcessing(10);

        assertThat(results).isEmpty();
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    @Test
    void updateStatus_transitionsFromReceivedToApproved() {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));

        repository.updateStatus(entity.paymentId(), "APPROVED", null);

        String status = jdbcClient
            .sql("SELECT status FROM wire_payments WHERE payment_id = :id")
            .param("id", entity.paymentId().toString())
            .query(String.class)
            .single();
        assertThat(status).isEqualTo("APPROVED");
    }

    @Test
    void updateStatus_setsRejectionReason() {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));

        repository.updateStatus(entity.paymentId(), "REJECTED", "sanctions match");

        String reason = jdbcClient
            .sql("SELECT rejection_reason FROM wire_payments WHERE payment_id = :id")
            .param("id", entity.paymentId().toString())
            .query(String.class)
            .single();
        assertThat(reason).isEqualTo("sanctions match");
    }

    @Test
    void updateStatus_throwsIllegalStateException_whenAlreadyTransitioned() {
        ensureMigration();
        var entity = TestPaymentFactory.makeEntity();
        repository.insertBatch(List.of(entity));
        repository.updateStatus(entity.paymentId(), "APPROVED", null);

        // Second transition from APPROVED should fail — guard is AND status = 'RECEIVED'
        assertThatThrownBy(() ->
            repository.updateStatus(entity.paymentId(), "REJECTED", "late change"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Status transition conflict");
    }

    @Test
    void updateStatus_throwsIllegalStateException_forUnknownPaymentId() {
        ensureMigration();
        assertThatThrownBy(() ->
            repository.updateStatus(UUID.randomUUID(), "APPROVED", null))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── Amount precision ──────────────────────────────────────────────────────

    @Test
    void insertBatch_preservesAmountPrecision() {
        ensureMigration();
        var txId   = UUID.randomUUID();
        var entity = new WirePaymentEntity(
            UUID.randomUUID(), txId,
            new BigDecimal("9999999999999999.1234"), // 20 sig figs, 4 decimal places
            "USD", java.time.Instant.now(), "WIRE",
            "12345678901", "021000021", "Alice Corp",
            "98765432101", "011000138", "Bob Inc",
            "user-1", "company-1", null, null,
            "RECEIVED", "v1");

        repository.insertBatch(List.of(entity));

        BigDecimal stored = jdbcClient
            .sql("SELECT amount FROM wire_payments WHERE transaction_id = :id")
            .param("id", txId.toString())
            .query(BigDecimal.class)
            .single();
        assertThat(stored).isEqualByComparingTo(new BigDecimal("9999999999999999.1234"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static DataSource dataSource() {
        var ds = new DriverManagerDataSource();
        ds.setUrl(ORACLE.getJdbcUrl());
        ds.setUsername(ORACLE.getUsername());
        ds.setPassword(ORACLE.getPassword());
        return ds;
    }

    private void truncateTables() {
        // Only safe after migration has run at least once
        if (!migrationRan) return;
        jdbcClient.sql("DELETE FROM wire_payment_addresses").update();
        jdbcClient.sql("DELETE FROM wire_payments").update();
    }

    /** Back-date created_at so the SKIP LOCKED poller considers this record eligible. */
    private void makeRecordEligibleForPolling(UUID paymentId) {
        jdbcClient.sql("""
                UPDATE wire_payments
                   SET created_at = SYSTIMESTAMP - INTERVAL '2' SECOND
                 WHERE payment_id = :id
                """)
            .param("id", paymentId.toString())
            .update();
    }
}
