-- Handoff contract for case management (ADR-0006) and the nightly run log.

-- The customer as it was when the alert was raised; linked transactions carry their posting day so the
-- export view can read them from one partition by primary key.
ALTER TABLE ${aml}.alert ADD COLUMN customer_snapshot JSONB;
ALTER TABLE ${aml}.alert_txn ADD COLUMN posting_date DATE;

CREATE TABLE ${aml}.nightly_run (
    run_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_date DATE NOT NULL,
    batch_id      VARCHAR(40) NOT NULL,
    step          VARCHAR(10) NOT NULL CHECK (step IN ('PROMOTE', 'DETECT', 'RETAIN')),
    status        VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    started_ts    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_ts      TIMESTAMP,
    message       VARCHAR(2000)
);
CREATE INDEX ix_nightly_run_date ON ${aml}.nightly_run (business_date);

-- One row per alert not yet handed off, with one self-contained JSON payload. Columns are a stable contract.
CREATE VIEW ${aml}.v_alert_export AS
SELECT a.alert_id,
       a.rule_code,
       a.business_date,
       a.account_id,
       a.customer_id,
       a.product_type,
       a.created_ts,
       jsonb_build_object(
           'alert_id', a.alert_id,
           'business_date', a.business_date,
           'summary', a.summary,
           'rule', jsonb_build_object('code', a.rule_code, 'name', r.name, 'version', a.rule_version),
           'account', jsonb_build_object('id', a.account_id, 'product_type', a.product_type),
           'customer', COALESCE(a.customer_snapshot, '{}'::jsonb) || jsonb_build_object('id', a.customer_id),
           'evidence', a.evidence,
           'transactions', (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                                'transaction_id', t.transaction_id, 'posting_date', t.posting_date, 'txn_ts', t.txn_ts,
                                'txn_type', t.txn_type, 'direction', t.direction, 'amount', t.amount, 'currency', t.currency,
                                'channel', t.channel, 'counterparty_name', t.counterparty_name,
                                'counterparty_country', t.counterparty_country) ORDER BY t.txn_ts, t.transaction_id), '[]'::jsonb)
                            FROM ${aml}.alert_txn l
                            JOIN ${mst}.txn t ON t.transaction_id = l.transaction_id AND t.posting_date = l.posting_date
                            WHERE l.alert_id = a.alert_id)
       ) AS payload
FROM ${aml}.alert a
JOIN ${aml}.rule r ON r.rule_id = a.rule_id
WHERE a.handed_off_ts IS NULL;

-- Called by case management after it has ingested alerts. Idempotent; returns how many were newly marked.
CREATE FUNCTION ${aml}.ack_alerts(p_alert_ids BIGINT[]) RETURNS INTEGER AS $$
DECLARE
    n INTEGER;
BEGIN
    UPDATE ${aml}.alert SET handed_off_ts = CURRENT_TIMESTAMP
     WHERE alert_id = ANY (p_alert_ids) AND handed_off_ts IS NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END;
$$ LANGUAGE plpgsql;
