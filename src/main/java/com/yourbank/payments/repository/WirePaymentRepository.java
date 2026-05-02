package com.yourbank.payments.repository;

import com.yourbank.payments.model.WirePaymentEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Oracle persistence for wire payments.
 *
 * Key decisions:
 *  - NamedParameterJdbcTemplate for batch inserts (true JDBC batching)
 *  - JdbcClient for reads/single updates (cleaner API)
 *  - Pre-filter duplicates before batch to avoid Oracle aborting entire batch
 *    on unique constraint violation
 *  - FOR UPDATE SKIP LOCKED for multi-pod safe polling
 *  - Optimistic guard on updateStatus (AND status = 'RECEIVED')
 */
@Repository
public class WirePaymentRepository {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedJdbc;

    private static final String INSERT_SQL = """
            INSERT INTO wire_payments (
                payment_id,            transaction_id,
                amount,                currency,
                value_date,            payment_type,
                debit_account_number,  debit_routing_number,  debit_holder_name,
                credit_account_number, credit_routing_number, credit_holder_name,
                user_id,               company_id,
                memo,                  purpose_code,
                status,                schema_version,
                created_at,            updated_at
            ) VALUES (
                :paymentId,            :transactionId,
                :amount,               :currency,
                :valueDate,            :paymentType,
                :debitAccountNumber,   :debitRoutingNumber,   :debitHolderName,
                :creditAccountNumber,  :creditRoutingNumber,  :creditHolderName,
                :userId,               :companyId,
                :memo,                 :purposeCode,
                'RECEIVED',            :schemaVersion,
                SYSTIMESTAMP,          SYSTIMESTAMP
            )
            """;

    public WirePaymentRepository(JdbcClient jdbcClient,
                                 NamedParameterJdbcTemplate namedJdbc) {
        this.jdbcClient = jdbcClient;
        this.namedJdbc  = namedJdbc;
    }

    // ── Batch insert ──────────────────────────────────────────────────────────
    @Transactional
    public void insertBatch(List<WirePaymentEntity> entities) {
        // Pre-filter duplicates — prevents Oracle aborting entire batch
        // on unique constraint violation during Kafka replay
        List<WirePaymentEntity> newEntities = entities.stream()
            .filter(e -> !existsByTransactionId(e.transactionId()))
            .toList();

        if (newEntities.isEmpty()) return;

        MapSqlParameterSource[] params = newEntities.stream()
            .map(this::toParams)
            .toArray(MapSqlParameterSource[]::new);

        namedJdbc.batchUpdate(INSERT_SQL, params);
    }

    // ── Idempotency check ─────────────────────────────────────────────────────
    public boolean existsByTransactionId(UUID transactionId) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(1) FROM wire_payments
                 WHERE transaction_id = :transactionId
                """)
                .param("transactionId", transactionId.toString())
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    // ── SKIP LOCKED polling — multi-pod safe ──────────────────────────────────
    @Transactional
    public List<WirePaymentEntity> pollForProcessing(int limit) {
        return jdbcClient.sql("""
                SELECT payment_id, transaction_id,
                       amount, currency, value_date, payment_type,
                       debit_account_number,  debit_routing_number,  debit_holder_name,
                       credit_account_number, credit_routing_number, credit_holder_name,
                       user_id, company_id, memo, purpose_code,
                       status, schema_version
                  FROM wire_payments
                 WHERE status     = 'RECEIVED'
                   AND created_at < SYSTIMESTAMP - INTERVAL '1' SECOND
                 ORDER BY created_at ASC
                FETCH FIRST :limit ROWS ONLY
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query(WirePaymentEntity.class)
                .list();
    }

    // ── Status transition ─────────────────────────────────────────────────────
    @Transactional
    public void updateStatus(UUID paymentId, String status, String rejectionReason) {
        int updated = jdbcClient.sql("""
                UPDATE wire_payments
                   SET status           = :status,
                       rejection_reason = :rejectionReason,
                       updated_at       = SYSTIMESTAMP
                 WHERE payment_id = :paymentId
                   AND status     = 'RECEIVED'
                """)
                .param("paymentId",       paymentId.toString())
                .param("status",          status)
                .param("rejectionReason", rejectionReason)
                .update();

        if (updated == 0) {
            throw new IllegalStateException(
                "Status transition conflict — paymentId=" + paymentId +
                " already transitioned or not found");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────
    private MapSqlParameterSource toParams(WirePaymentEntity e) {
        return new MapSqlParameterSource()
            .addValue("paymentId",           e.paymentId().toString())
            .addValue("transactionId",        e.transactionId().toString())
            .addValue("amount",               e.amount())
            .addValue("currency",             e.currency())
            .addValue("valueDate",            e.valueDate())
            .addValue("paymentType",          e.paymentType())
            .addValue("debitAccountNumber",   e.debitAccountNumber())
            .addValue("debitRoutingNumber",   e.debitRoutingNumber())
            .addValue("debitHolderName",      e.debitHolderName())
            .addValue("creditAccountNumber",  e.creditAccountNumber())
            .addValue("creditRoutingNumber",  e.creditRoutingNumber())
            .addValue("creditHolderName",     e.creditHolderName())
            .addValue("userId",               e.userId())
            .addValue("companyId",            e.companyId())
            .addValue("memo",                 e.memo())
            .addValue("purposeCode",          e.purposeCode())
            .addValue("schemaVersion",        e.schemaVersion());
    }
}
