CREATE TABLE ${aml}.batch_run (
    run_id     VARCHAR(36) PRIMARY KEY,
    job_name   VARCHAR(30) NOT NULL,
    status     VARCHAR(10) NOT NULL,
    started_ts DATETIME(6) NOT NULL,
    ended_ts   DATETIME(6),
    error_msg  VARCHAR(2000)
);

CREATE TABLE ${aml}.batch_run_entity (
    run_id       VARCHAR(36) NOT NULL REFERENCES ${aml}.batch_run (run_id),
    entity       VARCHAR(20) NOT NULL,
    rejected_cnt BIGINT NOT NULL,
    promoted_cnt BIGINT NOT NULL,
    PRIMARY KEY (run_id, entity)
);
