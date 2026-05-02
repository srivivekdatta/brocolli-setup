-- ─────────────────────────────────────────────────────────────────────────────
-- V1__create_wire_payments.sql
-- Wire payments schema — Oracle 19c
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Main payments table ───────────────────────────────────────────────────────
CREATE TABLE wire_payments (
    -- Identity
    payment_id              VARCHAR2(36)                NOT NULL,
    transaction_id          VARCHAR2(36)                NOT NULL,

    -- Transaction data
    amount                  NUMBER(19,4)                NOT NULL,   -- never FLOAT for money
    currency                CHAR(3)                     NOT NULL,
    value_date              TIMESTAMP WITH TIME ZONE    NOT NULL,
    payment_type            VARCHAR2(10)                NOT NULL,

    -- Debit account
    debit_account_number    VARCHAR2(17)                NOT NULL,
    debit_routing_number    CHAR(9)                     NOT NULL,
    debit_holder_name       VARCHAR2(140)               NOT NULL,

    -- Credit account
    credit_account_number   VARCHAR2(17)                NOT NULL,
    credit_routing_number   CHAR(9)                     NOT NULL,
    credit_holder_name      VARCHAR2(140)               NOT NULL,

    -- Party
    user_id                 VARCHAR2(100)               NOT NULL,
    company_id              VARCHAR2(100)               NOT NULL,

    -- Optional enrichment
    memo                    VARCHAR2(140),
    purpose_code            VARCHAR2(10),
    rejection_reason        VARCHAR2(500),

    -- Metadata
    status                  VARCHAR2(20)                NOT NULL,
    schema_version          VARCHAR2(10)                NOT NULL,

    -- Audit
    created_at              TIMESTAMP WITH TIME ZONE    DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE    DEFAULT SYSTIMESTAMP NOT NULL,

    -- Constraints
    CONSTRAINT pk_wire_payments       PRIMARY KEY (payment_id),
    CONSTRAINT uq_wire_transaction_id UNIQUE      (transaction_id),
    CONSTRAINT chk_wire_status        CHECK       (status IN ('RECEIVED','APPROVED','HELD','REJECTED')),
    CONSTRAINT chk_wire_payment_type  CHECK       (payment_type IN ('WIRE','ACH','FEDNOW','CHIPS','FPS')),
    CONSTRAINT chk_wire_amount        CHECK       (amount > 0)
);

-- ── Indexes ───────────────────────────────────────────────────────────────────

-- SKIP LOCKED poller index — covers WHERE status + ORDER BY created_at exactly
-- Partial index: only indexes RECEIVED rows — shrinks as rows are processed
CREATE INDEX idx_wire_status_created
    ON wire_payments (status, created_at)
    WHERE status = 'RECEIVED';

-- Fraud investigation — account-level lookups
CREATE INDEX idx_wire_debit_acct
    ON wire_payments (debit_account_number, created_at DESC);

CREATE INDEX idx_wire_credit_acct
    ON wire_payments (credit_account_number, created_at DESC);

-- Company-level reporting
CREATE INDEX idx_wire_company_created
    ON wire_payments (company_id, created_at DESC);


-- ── Address table (separate — keeps wire_payments row narrow) ─────────────────
CREATE TABLE wire_payment_addresses (
    address_id              VARCHAR2(36)                NOT NULL,
    payment_id              VARCHAR2(36)                NOT NULL,
    address_role            VARCHAR2(10)                NOT NULL,   -- DEBIT | CREDIT

    -- ISO 20022 PostalAddress24 fields
    address_type            VARCHAR2(10),
    street_name             VARCHAR2(70),
    building_number         VARCHAR2(16),
    building_name           VARCHAR2(35),
    floor                   VARCHAR2(70),
    post_box                VARCHAR2(16),
    room                    VARCHAR2(70),
    post_code               VARCHAR2(16),
    town_name               VARCHAR2(35),
    town_location_name      VARCHAR2(35),
    district_name           VARCHAR2(35),
    country_sub_div         VARCHAR2(35),
    country                 CHAR(2)                     NOT NULL,
    address_line_1          VARCHAR2(70),
    address_line_2          VARCHAR2(70),
    address_line_3          VARCHAR2(70),
    created_at              TIMESTAMP WITH TIME ZONE    DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_wire_addresses      PRIMARY KEY (address_id),
    CONSTRAINT fk_wire_addr_payment   FOREIGN KEY (payment_id)
        REFERENCES wire_payments (payment_id),
    CONSTRAINT chk_wire_addr_role     CHECK (address_role IN ('DEBIT','CREDIT')),
    CONSTRAINT chk_wire_country       CHECK (REGEXP_LIKE(country, '^[A-Z]{2}$'))
);

CREATE INDEX idx_wire_addr_payment
    ON wire_payment_addresses (payment_id);
