-- Scale design (ADR-0005). Pre-release: staging and master transaction tables are recreated.

-- 1. Batch sequence number names the batch's staging partitions; load_reject replaces row-level reject marks.
ALTER TABLE ${aml}.load_batch ADD COLUMN batch_seq BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX ux_load_batch_seq ON ${aml}.load_batch (batch_seq);

CREATE TABLE ${aml}.load_reject (
    batch_id     VARCHAR(40) NOT NULL REFERENCES ${aml}.load_batch (batch_id),
    entity       VARCHAR(20) NOT NULL,
    rule_id      VARCHAR(20) NOT NULL,
    reason       VARCHAR(400) NOT NULL,
    reject_count BIGINT NOT NULL,
    sample_keys  JSONB NOT NULL,
    PRIMARY KEY (batch_id, entity, rule_id)
);

-- 2. Staging: list-partitioned by batch_id, one unlogged partition per batch and entity (created by trigger).
DROP TABLE ${stg}.customer;
DROP TABLE ${stg}.account;
DROP TABLE ${stg}.txn;
CREATE SEQUENCE ${stg}.stg_seq CACHE 1000;

CREATE TABLE ${stg}.customer (
    stg_id                BIGINT NOT NULL DEFAULT nextval('${stg}.stg_seq'),
    batch_id              VARCHAR(40) NOT NULL,
    customer_id           VARCHAR(40),
    customer_type         VARCHAR(20),
    full_name             VARCHAR(200),
    birth_or_formation_dt DATE,
    country_code          VARCHAR(3),
    state_code            VARCHAR(3),
    customer_since        DATE,
    status                VARCHAR(20)
) PARTITION BY LIST (batch_id);

CREATE TABLE ${stg}.account (
    stg_id              BIGINT NOT NULL DEFAULT nextval('${stg}.stg_seq'),
    batch_id            VARCHAR(40) NOT NULL,
    account_id          VARCHAR(40),
    primary_customer_id VARCHAR(40),
    product_type        VARCHAR(20),
    product_subtype     VARCHAR(40),
    status              VARCHAR(20),
    open_date           DATE,
    close_date          DATE,
    currency            VARCHAR(3),
    branch_code         VARCHAR(20),
    credit_limit        DECIMAL(19,4)
) PARTITION BY LIST (batch_id);

CREATE TABLE ${stg}.txn (
    batch_id               VARCHAR(40) NOT NULL,
    transaction_id         VARCHAR(60),
    account_id             VARCHAR(40),
    txn_ts                 TIMESTAMP,
    posting_date           DATE,
    txn_type               VARCHAR(30),
    direction              VARCHAR(6),
    amount                 DECIMAL(19,4),
    currency               VARCHAR(3),
    channel                VARCHAR(20),
    counterparty_name      VARCHAR(200),
    counterparty_account   VARCHAR(60),
    counterparty_country   VARCHAR(3),
    merchant_category_code VARCHAR(4),
    description            VARCHAR(400)
) PARTITION BY LIST (batch_id);

-- Registering a batch (the ETL inserts into load_batch) creates its staging partitions. No indexes: validation scans.
CREATE FUNCTION ${aml}.create_stg_partitions() RETURNS trigger AS $$
BEGIN
    EXECUTE format('CREATE UNLOGGED TABLE ${stg}.customer_b%s PARTITION OF ${stg}.customer FOR VALUES IN (%L)', NEW.batch_seq, NEW.batch_id);
    EXECUTE format('CREATE UNLOGGED TABLE ${stg}.account_b%s PARTITION OF ${stg}.account FOR VALUES IN (%L)', NEW.batch_seq, NEW.batch_id);
    EXECUTE format('CREATE UNLOGGED TABLE ${stg}.txn_b%s PARTITION OF ${stg}.txn FOR VALUES IN (%L)', NEW.batch_seq, NEW.batch_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_load_batch_stg_partitions AFTER INSERT ON ${aml}.load_batch
    FOR EACH ROW EXECUTE FUNCTION ${aml}.create_stg_partitions();

-- 3. Master transactions: one range partition per posting day, attached by the engine after an offline build.
--    Primary key per partition (transaction_id, posting_date); no foreign keys (validation enforces references).
DROP TABLE ${mst}.txn;
CREATE TABLE ${mst}.txn (
    transaction_id         VARCHAR(60) NOT NULL,
    account_id             VARCHAR(40) NOT NULL,
    txn_ts                 TIMESTAMP NOT NULL,
    posting_date           DATE NOT NULL,
    txn_type               VARCHAR(30) NOT NULL,
    direction              VARCHAR(6) NOT NULL,
    amount                 DECIMAL(19,4) NOT NULL,
    currency               VARCHAR(3) NOT NULL,
    channel                VARCHAR(20),
    counterparty_name      VARCHAR(200),
    counterparty_account   VARCHAR(60),
    counterparty_country   VARCHAR(3),
    merchant_category_code VARCHAR(4),
    description            VARCHAR(400),
    batch_id               VARCHAR(40) NOT NULL,
    created_ts             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transaction_id, posting_date)
) PARTITION BY RANGE (posting_date);
CREATE INDEX ix_mst_txn_acct_post ON ${mst}.txn (account_id, posting_date);
