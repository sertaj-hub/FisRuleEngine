-- Hardening (ADR-0007): who did it, immutable handed-off alerts, operations views.

-- 1. Audit: the database user behind each action.
ALTER TABLE ${aml}.rule ADD COLUMN created_by VARCHAR(100) NOT NULL DEFAULT current_user;
ALTER TABLE ${aml}.load_batch ADD COLUMN registered_by VARCHAR(100) NOT NULL DEFAULT current_user;
ALTER TABLE ${aml}.load_batch ADD COLUMN claimed_ts TIMESTAMP;
ALTER TABLE ${aml}.alert ADD COLUMN handed_off_by VARCHAR(100);

CREATE OR REPLACE FUNCTION ${aml}.ack_alerts(p_alert_ids BIGINT[]) RETURNS INTEGER AS $$
DECLARE
    n INTEGER;
BEGIN
    UPDATE ${aml}.alert SET handed_off_ts = CURRENT_TIMESTAMP, handed_off_by = session_user
     WHERE alert_id = ANY (p_alert_ids) AND handed_off_ts IS NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END;
$$ LANGUAGE plpgsql;

-- 2. A handed-off alert is what case management received; it must not change or disappear. The bypass is for a
--    purge under change control (SET aml.allow_alert_purge = 'on'). Revoke UPDATE/DELETE from other roles for real protection.
CREATE FUNCTION ${aml}.protect_handed_off_alert() RETURNS trigger AS $$
BEGIN
    IF OLD.handed_off_ts IS NOT NULL AND COALESCE(current_setting('aml.allow_alert_purge', true), 'off') <> 'on' THEN
        RAISE EXCEPTION 'alert % was handed off to case management and is immutable', OLD.alert_id;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_alert_immutable BEFORE UPDATE OR DELETE ON ${aml}.alert
    FOR EACH ROW EXECUTE FUNCTION ${aml}.protect_handed_off_alert();

-- 3. Operations views.
CREATE VIEW ${aml}.v_ops_batches AS
SELECT b.batch_id, b.business_date, b.status, b.registered_by, b.registered_ts, b.loaded_ts, b.claimed_ts, b.promoted_ts, b.cleaned_ts,
       b.superseded_by, b.error_msg,
       MAX(CASE WHEN e.entity = 'TXN' THEN e.staged_cnt END)   AS txn_staged,
       MAX(CASE WHEN e.entity = 'TXN' THEN e.rejected_cnt END) AS txn_rejected,
       MAX(CASE WHEN e.entity = 'TXN' THEN e.promoted_cnt END) AS txn_promoted
FROM ${aml}.load_batch b
LEFT JOIN ${aml}.load_batch_entity e ON e.batch_id = b.batch_id
WHERE b.business_date >= CURRENT_DATE - 45
GROUP BY b.batch_id;

CREATE VIEW ${aml}.v_ops_failures AS
SELECT 'BATCH' AS kind, batch_id AS reference, business_date, error_msg AS message, registered_ts AS at_ts
  FROM ${aml}.load_batch WHERE status = 'FAILED' AND business_date >= CURRENT_DATE - 7
UNION ALL
SELECT 'RULE', rule_code, business_date, error_msg, started_ts
  FROM ${aml}.rule_run WHERE status = 'FAILED' AND started_ts >= CURRENT_TIMESTAMP - INTERVAL '7 days'
UNION ALL
SELECT 'NIGHTLY', step || ' ' || batch_id, business_date, message, started_ts
  FROM ${aml}.nightly_run WHERE status = 'FAILED' AND started_ts >= CURRENT_TIMESTAMP - INTERVAL '7 days';

CREATE VIEW ${aml}.v_ops_alert_backlog AS
SELECT business_date, COUNT(*) AS unsent_alerts, MIN(created_ts) AS oldest_created_ts
FROM ${aml}.alert WHERE handed_off_ts IS NULL
GROUP BY business_date;
