-- Rules: one row per version of a configured template (ADR-0004). No severity.
CREATE TABLE ${aml}.rule (
    rule_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_code     VARCHAR(60) NOT NULL,
    version       INTEGER NOT NULL,
    name          VARCHAR(200) NOT NULL,
    description   VARCHAR(2000),
    template_code VARCHAR(40) NOT NULL,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    suppress_days INTEGER NOT NULL DEFAULT 0,
    config        JSONB NOT NULL,
    created_ts    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (rule_code, version)
);
CREATE UNIQUE INDEX ux_rule_one_active ON ${aml}.rule (rule_code) WHERE status = 'ACTIVE';

CREATE TABLE ${aml}.rule_run (
    run_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id        BIGINT NOT NULL REFERENCES ${aml}.rule (rule_id),
    rule_code      VARCHAR(60) NOT NULL,
    business_date  DATE NOT NULL,
    status         VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    started_ts     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_ts       TIMESTAMP,
    alerts_created BIGINT NOT NULL DEFAULT 0,
    error_msg      VARCHAR(2000)
);
CREATE INDEX ix_rule_run_date ON ${aml}.rule_run (business_date, rule_code);

-- Alerts: one per rule, account and business date, addressed to the account's primary customer.
CREATE TABLE ${aml}.alert (
    alert_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id        BIGINT NOT NULL REFERENCES ${aml}.rule (rule_id),
    rule_code      VARCHAR(60) NOT NULL,
    rule_version   INTEGER NOT NULL,
    business_date  DATE NOT NULL,
    account_id     VARCHAR(40) NOT NULL,
    customer_id    VARCHAR(40) NOT NULL,
    product_type   VARCHAR(20) NOT NULL,
    summary        VARCHAR(400) NOT NULL,
    evidence       JSONB NOT NULL,
    run_id         BIGINT REFERENCES ${aml}.rule_run (run_id),
    created_ts     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handed_off_ts  TIMESTAMP,
    UNIQUE (rule_code, account_id, business_date)
);
CREATE INDEX ix_alert_date ON ${aml}.alert (business_date);
CREATE INDEX ix_alert_unsent ON ${aml}.alert (handed_off_ts) WHERE handed_off_ts IS NULL;

CREATE TABLE ${aml}.alert_txn (
    alert_id       BIGINT NOT NULL REFERENCES ${aml}.alert (alert_id) ON DELETE CASCADE,
    transaction_id VARCHAR(60) NOT NULL,
    PRIMARY KEY (alert_id, transaction_id)
);
