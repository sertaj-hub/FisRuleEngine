-- Alert delivery to case management: a reconciled outbox (ADR-0008).

CREATE TABLE ${aml}.alert_delivery (
    delivery_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_date     DATE NOT NULL,
    revision          INTEGER NOT NULL,
    status            VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'READY', 'CONFIRMED', 'MISMATCH')),
    created_ts        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_ts          TIMESTAMP,
    alert_count       INTEGER,
    rule_counts       JSONB,
    checksum          VARCHAR(64),
    confirmed_ts      TIMESTAMP,
    confirmed_by      VARCHAR(100),
    received_count    INTEGER,
    received_checksum VARCHAR(64),
    UNIQUE (business_date, revision)
);

ALTER TABLE ${aml}.alert ADD COLUMN delivery_id BIGINT REFERENCES ${aml}.alert_delivery (delivery_id);
CREATE INDEX ix_alert_delivery ON ${aml}.alert (delivery_id);

-- Append-only: tells the consumer an alert it already received no longer hits after a re-run on corrected data.
CREATE TABLE ${aml}.alert_event (
    event_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id   BIGINT NOT NULL REFERENCES ${aml}.alert (alert_id) ON DELETE CASCADE,
    event_type VARCHAR(12) NOT NULL CHECK (event_type IN ('WITHDRAWN')),
    reason     VARCHAR(400) NOT NULL,
    created_ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX ux_alert_event_once ON ${aml}.alert_event (alert_id, event_type);

-- The consumer could not ingest these alerts. Resolved by the engine team (resolved_ts).
CREATE TABLE ${aml}.alert_rejection (
    rejection_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id     BIGINT NOT NULL REFERENCES ${aml}.alert (alert_id) ON DELETE CASCADE,
    reason       VARCHAR(400) NOT NULL,
    rejected_by  VARCHAR(100) NOT NULL DEFAULT session_user,
    rejected_ts  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_ts  TIMESTAMP,
    resolved_by  VARCHAR(100)
);
CREATE INDEX ix_alert_rejection_open ON ${aml}.alert_rejection (alert_id) WHERE resolved_ts IS NULL;

-- SHA-256 (hex) of the alert ids sorted ascending and joined with commas. Both sides use this formula.
CREATE FUNCTION ${aml}.compute_alert_checksum(p_alert_ids BIGINT[]) RETURNS TEXT AS $$
    SELECT encode(sha256(convert_to(COALESCE((SELECT string_agg(i::text, ',' ORDER BY i) FROM unnest(p_alert_ids) AS i), ''), 'UTF8')), 'hex');
$$ LANGUAGE sql IMMUTABLE;

-- The consumer reports what it ingested; the engine compares with the control totals written at publish time.
CREATE FUNCTION ${aml}.confirm_delivery(p_delivery_id BIGINT, p_received_count INTEGER, p_received_checksum TEXT) RETURNS TEXT AS $$
DECLARE
    d ${aml}.alert_delivery%ROWTYPE;
BEGIN
    SELECT * INTO d FROM ${aml}.alert_delivery WHERE delivery_id = p_delivery_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown delivery %', p_delivery_id;
    END IF;
    IF d.status = 'OPEN' THEN
        RAISE EXCEPTION 'delivery % is not published yet (status OPEN)', p_delivery_id;
    END IF;
    IF d.status = 'CONFIRMED' THEN
        IF d.received_count = p_received_count AND d.received_checksum = lower(p_received_checksum) THEN
            RETURN 'CONFIRMED';
        END IF;
        RAISE EXCEPTION 'delivery % is already CONFIRMED with different numbers', p_delivery_id;
    END IF;
    IF d.alert_count = p_received_count AND d.checksum = lower(p_received_checksum) THEN
        UPDATE ${aml}.alert_delivery SET status = 'CONFIRMED', confirmed_ts = CURRENT_TIMESTAMP, confirmed_by = session_user,
               received_count = p_received_count, received_checksum = lower(p_received_checksum)
         WHERE delivery_id = p_delivery_id;
        UPDATE ${aml}.alert SET handed_off_ts = CURRENT_TIMESTAMP, handed_off_by = session_user
         WHERE delivery_id = p_delivery_id AND handed_off_ts IS NULL;
        RETURN 'CONFIRMED';
    END IF;
    UPDATE ${aml}.alert_delivery SET status = 'MISMATCH', received_count = p_received_count, received_checksum = lower(p_received_checksum)
     WHERE delivery_id = p_delivery_id;
    RETURN 'MISMATCH';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION ${aml}.reject_alerts(p_alert_ids BIGINT[], p_reason TEXT) RETURNS INTEGER AS $$
DECLARE
    n INTEGER;
BEGIN
    INSERT INTO ${aml}.alert_rejection (alert_id, reason)
    SELECT a.alert_id, left(p_reason, 400) FROM ${aml}.alert a WHERE a.alert_id = ANY (p_alert_ids);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END;
$$ LANGUAGE plpgsql;

-- The consumer sees only published deliveries, and only their unsent alerts.
DROP VIEW ${aml}.v_alert_export;
CREATE VIEW ${aml}.v_alert_export AS
SELECT a.alert_id,
       a.delivery_id,
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
           'delivery', jsonb_build_object('id', d.delivery_id, 'revision', d.revision),
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
JOIN ${aml}.alert_delivery d ON d.delivery_id = a.delivery_id
JOIN ${aml}.rule r ON r.rule_id = a.rule_id
WHERE a.handed_off_ts IS NULL AND d.status IN ('READY', 'MISMATCH');

CREATE VIEW ${aml}.v_alert_delivery AS
SELECT delivery_id, business_date, revision, status, alert_count, rule_counts, checksum, ready_ts, confirmed_ts
FROM ${aml}.alert_delivery
WHERE status <> 'OPEN';

CREATE VIEW ${aml}.v_alert_events AS
SELECT e.event_id, e.alert_id, e.event_type, e.reason, e.created_ts, a.rule_code, a.business_date, a.account_id, a.delivery_id
FROM ${aml}.alert_event e
JOIN ${aml}.alert a ON a.alert_id = e.alert_id;

CREATE VIEW ${aml}.v_alert_reconciliation AS
SELECT d.delivery_id, d.business_date, d.revision, d.status,
       d.alert_count AS expected_count,
       (SELECT COUNT(*) FROM ${aml}.alert a WHERE a.delivery_id = d.delivery_id) AS created_alerts,
       (SELECT COUNT(*) FROM ${aml}.alert a WHERE a.delivery_id = d.delivery_id AND a.handed_off_ts IS NOT NULL) AS acknowledged_alerts,
       (SELECT COUNT(*) FROM ${aml}.alert a WHERE a.delivery_id = d.delivery_id AND a.handed_off_ts IS NULL) AS pending_alerts,
       (SELECT COUNT(DISTINCT r.alert_id) FROM ${aml}.alert_rejection r JOIN ${aml}.alert a ON a.alert_id = r.alert_id
         WHERE a.delivery_id = d.delivery_id AND r.resolved_ts IS NULL) AS rejected_unresolved,
       (SELECT COUNT(*) FROM ${aml}.alert_event e JOIN ${aml}.alert a ON a.alert_id = e.alert_id WHERE a.delivery_id = d.delivery_id) AS withdrawn_alerts,
       d.received_count, d.checksum, d.received_checksum, d.ready_ts, d.confirmed_ts,
       CASE WHEN d.status IN ('READY', 'MISMATCH')
            THEN round(CAST(extract(epoch FROM (CURRENT_TIMESTAMP - d.ready_ts)) / 3600 AS numeric), 1) END AS hours_unconfirmed
FROM ${aml}.alert_delivery d
WHERE d.status <> 'OPEN';

-- The backlog is what the consumer can see and has not confirmed.
CREATE OR REPLACE VIEW ${aml}.v_ops_alert_backlog AS
SELECT a.business_date, COUNT(*) AS unsent_alerts, MIN(a.created_ts) AS oldest_created_ts
FROM ${aml}.alert a
JOIN ${aml}.alert_delivery d ON d.delivery_id = a.delivery_id
WHERE a.handed_off_ts IS NULL AND d.status IN ('READY', 'MISMATCH')
GROUP BY a.business_date;
