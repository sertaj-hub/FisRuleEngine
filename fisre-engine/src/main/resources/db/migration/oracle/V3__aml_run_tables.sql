CREATE TABLE ${aml}.batch_run (
    run_id     VARCHAR2(36) PRIMARY KEY,
    job_name   VARCHAR2(30) NOT NULL,
    status     VARCHAR2(10) NOT NULL,
    started_ts TIMESTAMP(6) NOT NULL,
    ended_ts   TIMESTAMP(6),
    error_msg  VARCHAR2(2000)
);

CREATE TABLE ${aml}.batch_run_entity (
    run_id       VARCHAR2(36) NOT NULL REFERENCES ${aml}.batch_run (run_id),
    entity       VARCHAR2(20) NOT NULL,
    rejected_cnt NUMBER(19) NOT NULL,
    promoted_cnt NUMBER(19) NOT NULL,
    PRIMARY KEY (run_id, entity)
);
