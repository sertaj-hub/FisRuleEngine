-- MST: validated, de-duplicated master data. Detection reads only from here.
CREATE TABLE ${mst}.ref_txn_type (
    txn_type    VARCHAR2(30) PRIMARY KEY,
    is_cash     CHAR(1 CHAR) NOT NULL,
    description VARCHAR2(200)
);

CREATE TABLE ${mst}.customer (
    customer_id           VARCHAR2(40) PRIMARY KEY,
    customer_type         VARCHAR2(20) NOT NULL,
    full_name             VARCHAR2(200) NOT NULL,
    birth_or_formation_dt DATE,
    country_code          VARCHAR2(3),
    state_code            VARCHAR2(3),
    customer_since        DATE,
    status                VARCHAR2(20),
    load_id               VARCHAR2(40),
    created_ts            TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
    updated_ts            TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE ${mst}.account (
    account_id          VARCHAR2(40) PRIMARY KEY,
    primary_customer_id VARCHAR2(40) NOT NULL REFERENCES ${mst}.customer (customer_id),
    product_type        VARCHAR2(20) NOT NULL,
    product_subtype     VARCHAR2(40),
    status              VARCHAR2(20) NOT NULL,
    open_date           DATE NOT NULL,
    close_date          DATE,
    currency            VARCHAR2(3),
    branch_code         VARCHAR2(20),
    credit_limit        NUMBER(19,4),
    load_id             VARCHAR2(40),
    created_ts          TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
    updated_ts          TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ${mst}.ix_mst_account_customer ON ${mst}.account (primary_customer_id);

CREATE TABLE ${mst}.txn (
    transaction_id        VARCHAR2(60) PRIMARY KEY,
    account_id            VARCHAR2(40) NOT NULL REFERENCES ${mst}.account (account_id),
    txn_ts                TIMESTAMP(6) NOT NULL,
    posting_date          DATE NOT NULL,
    txn_type              VARCHAR2(30) NOT NULL REFERENCES ${mst}.ref_txn_type (txn_type),
    direction             VARCHAR2(6) NOT NULL,
    amount                NUMBER(19,4) NOT NULL,
    currency              VARCHAR2(3) NOT NULL,
    channel               VARCHAR2(20),
    counterparty_name     VARCHAR2(200),
    counterparty_account  VARCHAR2(60),
    counterparty_country  VARCHAR2(3),
    merchant_category_code VARCHAR2(4),
    description           VARCHAR2(400),
    load_id               VARCHAR2(40),
    created_ts            TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ${mst}.ix_mst_txn_acct_post ON ${mst}.txn (account_id, posting_date);
CREATE INDEX ${mst}.ix_mst_txn_post ON ${mst}.txn (posting_date);
