-- ML anomaly detection (ADR-0009): model registry and scores. Written by fisre-ml, read by the ML_SCORE template.
CREATE TABLE ${aml}.ml_model (
    model_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_name      VARCHAR(60) NOT NULL,
    model_version   VARCHAR(60) NOT NULL UNIQUE,
    status          VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
    trained_ts      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    train_from      DATE NOT NULL,
    train_to        DATE NOT NULL,
    train_rows      BIGINT NOT NULL,
    params          JSONB NOT NULL,
    artifact_path   VARCHAR(500) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    created_by      VARCHAR(100) NOT NULL DEFAULT CURRENT_USER
);
CREATE UNIQUE INDEX ux_ml_model_one_active ON ${aml}.ml_model (model_name) WHERE status = 'ACTIVE';

CREATE TABLE ${aml}.ml_score (
    model_version   VARCHAR(60) NOT NULL REFERENCES ${aml}.ml_model (model_version),
    as_of_date      DATE NOT NULL,
    account_id      VARCHAR(40) NOT NULL,
    product_type    VARCHAR(20) NOT NULL,
    score           NUMERIC(8,6) NOT NULL CHECK (score >= 0 AND score <= 1),
    rank_in_product INTEGER NOT NULL,
    explanation     JSONB NOT NULL,
    scored_ts       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (model_version, as_of_date, account_id)
);
CREATE INDEX ix_ml_score_day ON ${aml}.ml_score (as_of_date, score);

CREATE VIEW ${aml}.v_ml_scores AS
SELECT m.model_name, s.model_version, s.as_of_date, s.account_id, s.product_type, s.score, s.rank_in_product, s.explanation
FROM ${aml}.ml_score s JOIN ${aml}.ml_model m ON m.model_version = s.model_version
WHERE m.status = 'ACTIVE';
