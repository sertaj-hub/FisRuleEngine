-- STG: landing area loaded by the bank's ETL. Typed but unconstrained; the engine validates and promotes.
-- rec_status: NEW -> PROCESSED | REJECTED
CREATE TABLE ${stg}.customer (
    stg_id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    load_id               VARCHAR(40),
    rec_status            VARCHAR(10) DEFAULT 'NEW' NOT NULL,
    reject_reason         VARCHAR(400),
    customer_id           VARCHAR(40),
    customer_type         VARCHAR(20),
    full_name             VARCHAR(200),
    birth_or_formation_dt DATE,
    country_code          VARCHAR(3),
    state_code            VARCHAR(3),
    customer_since        DATE,
    status                VARCHAR(20)
);
CREATE INDEX ix_stg_customer_status ON ${stg}.customer (rec_status);
CREATE INDEX ix_stg_customer_key    ON ${stg}.customer (customer_id);

CREATE TABLE ${stg}.account (
    stg_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    load_id             VARCHAR(40),
    rec_status          VARCHAR(10) DEFAULT 'NEW' NOT NULL,
    reject_reason       VARCHAR(400),
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
);
CREATE INDEX ix_stg_account_status ON ${stg}.account (rec_status);
CREATE INDEX ix_stg_account_key    ON ${stg}.account (account_id);

CREATE TABLE ${stg}.txn (
    stg_id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    load_id               VARCHAR(40),
    rec_status            VARCHAR(10) DEFAULT 'NEW' NOT NULL,
    reject_reason         VARCHAR(400),
    transaction_id        VARCHAR(60),
    account_id            VARCHAR(40),
    txn_ts                TIMESTAMP,
    posting_date          DATE,
    txn_type              VARCHAR(30),
    direction             VARCHAR(6),
    amount                DECIMAL(19,4),
    currency              VARCHAR(3),
    channel               VARCHAR(20),
    counterparty_name     VARCHAR(200),
    counterparty_account  VARCHAR(60),
    counterparty_country  VARCHAR(3),
    merchant_category_code VARCHAR(4),
    description           VARCHAR(400)
);
CREATE INDEX ix_stg_txn_status ON ${stg}.txn (rec_status);
CREATE INDEX ix_stg_txn_key    ON ${stg}.txn (transaction_id);
