-- MST: validated, de-duplicated master data. Detection reads only from here.
CREATE TABLE ${mst}.ref_txn_type (
    txn_type    VARCHAR(30) PRIMARY KEY,
    is_cash     CHAR(1) NOT NULL,
    description VARCHAR(200)
);

CREATE TABLE ${mst}.customer (
    customer_id           VARCHAR(40) PRIMARY KEY,
    customer_type         VARCHAR(20) NOT NULL,
    full_name             VARCHAR(200) NOT NULL,
    birth_or_formation_dt DATE,
    country_code          VARCHAR(3),
    state_code            VARCHAR(3),
    customer_since        DATE,
    status                VARCHAR(20),
    load_id               VARCHAR(40),
    created_ts            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_ts            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ${mst}.account (
    account_id          VARCHAR(40) PRIMARY KEY,
    primary_customer_id VARCHAR(40) NOT NULL REFERENCES ${mst}.customer (customer_id),
    product_type        VARCHAR(20) NOT NULL,
    product_subtype     VARCHAR(40),
    status              VARCHAR(20) NOT NULL,
    open_date           DATE NOT NULL,
    close_date          DATE,
    currency            VARCHAR(3),
    branch_code         VARCHAR(20),
    credit_limit        DECIMAL(19,4),
    load_id             VARCHAR(40),
    created_ts          TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_ts          TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX ix_mst_account_customer ON ${mst}.account (primary_customer_id);

CREATE TABLE ${mst}.txn (
    transaction_id        VARCHAR(60) PRIMARY KEY,
    account_id            VARCHAR(40) NOT NULL REFERENCES ${mst}.account (account_id),
    txn_ts                TIMESTAMP NOT NULL,
    posting_date          DATE NOT NULL,
    txn_type              VARCHAR(30) NOT NULL REFERENCES ${mst}.ref_txn_type (txn_type),
    direction             VARCHAR(6) NOT NULL,
    amount                DECIMAL(19,4) NOT NULL,
    currency              VARCHAR(3) NOT NULL,
    channel               VARCHAR(20),
    counterparty_name     VARCHAR(200),
    counterparty_account  VARCHAR(60),
    counterparty_country  VARCHAR(3),
    merchant_category_code VARCHAR(4),
    description           VARCHAR(400),
    load_id               VARCHAR(40),
    created_ts            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX ix_mst_txn_acct_post ON ${mst}.txn (account_id, posting_date);
CREATE INDEX ix_mst_txn_post      ON ${mst}.txn (posting_date);
