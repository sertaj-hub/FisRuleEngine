-- A batch = one business date's delivery of customer, account and txn rows. It succeeds or fails as a whole.
-- Status: LOADING -> LOADED -> PROMOTING -> PROMOTED -> CLEANED
--                                   \-> FAILED     (PROMOTED/CLEANED -> SUPERSEDED when a later batch replaces it)
CREATE TABLE ${aml}.load_batch (
    batch_id      VARCHAR(40) PRIMARY KEY,
    business_date DATE NOT NULL,
    status        VARCHAR(12) NOT NULL DEFAULT 'LOADING'
                  CHECK (status IN ('LOADING', 'LOADED', 'PROMOTING', 'PROMOTED', 'CLEANED', 'FAILED', 'SUPERSEDED')),
    registered_ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    loaded_ts     TIMESTAMP,
    promoted_ts   TIMESTAMP,
    cleaned_ts    TIMESTAMP,
    superseded_by VARCHAR(40),
    error_msg     VARCHAR(2000)
);
-- At most one live (promoted) batch per business date.
CREATE UNIQUE INDEX ux_load_batch_live_date ON ${aml}.load_batch (business_date)
    WHERE status IN ('PROMOTED', 'CLEANED');

CREATE TABLE ${aml}.load_batch_entity (
    batch_id     VARCHAR(40) NOT NULL REFERENCES ${aml}.load_batch (batch_id),
    entity       VARCHAR(20) NOT NULL,
    staged_cnt   BIGINT NOT NULL DEFAULT 0,
    rejected_cnt BIGINT NOT NULL DEFAULT 0,
    promoted_cnt BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (batch_id, entity)
);
