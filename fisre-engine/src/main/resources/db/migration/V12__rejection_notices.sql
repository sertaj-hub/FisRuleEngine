-- Rejections by case management: a durable notice with audit and a tracked production remediation (REQ-REJ-*).

CREATE TABLE ${aml}.alert_rejection_notice (
    notice_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    delivery_id       BIGINT NOT NULL REFERENCES ${aml}.alert_delivery (delivery_id),
    reason            VARCHAR(400) NOT NULL,
    alert_count       INTEGER NOT NULL,
    rejected_by       VARCHAR(100) NOT NULL DEFAULT session_user,
    rejected_ts       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status            VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution_action VARCHAR(20) CHECK (resolution_action IN ('FIXED_REREAD', 'HANDLED_MANUALLY')),
    resolution_note   VARCHAR(1000),
    resolved_by       VARCHAR(100),
    resolved_ts       TIMESTAMP
);
CREATE INDEX ix_rejection_notice_open ON ${aml}.alert_rejection_notice (rejected_ts) WHERE status = 'OPEN';

ALTER TABLE ${aml}.alert_rejection ADD COLUMN notice_id BIGINT REFERENCES ${aml}.alert_rejection_notice (notice_id);
-- Alerts of resolved-as-handled-manually notices are excluded from the delivery's control totals when it is confirmed.
ALTER TABLE ${aml}.alert_delivery ADD COLUMN excluded_count INTEGER NOT NULL DEFAULT 0;

CREATE OR REPLACE FUNCTION ${aml}.reject_alerts(p_alert_ids BIGINT[], p_reason TEXT) RETURNS INTEGER AS $$
DECLARE
    total INTEGER := 0;
    r     RECORD;
    nid   BIGINT;
BEGIN
    IF p_reason IS NULL OR btrim(p_reason) = '' THEN
        RAISE EXCEPTION 'a reason is required to reject alerts';
    END IF;
    FOR r IN SELECT a.delivery_id, array_agg(a.alert_id) AS ids
               FROM ${aml}.alert a
              WHERE a.alert_id = ANY (p_alert_ids) AND a.delivery_id IS NOT NULL
              GROUP BY a.delivery_id ORDER BY a.delivery_id
    LOOP
        INSERT INTO ${aml}.alert_rejection_notice (delivery_id, reason, alert_count)
        VALUES (r.delivery_id, left(p_reason, 400), cardinality(r.ids)) RETURNING notice_id INTO nid;
        INSERT INTO ${aml}.alert_rejection (alert_id, reason, notice_id)
        SELECT unnest(r.ids), left(p_reason, 400), nid;
        -- Lets monitoring or paging tools react at once; the notice row is the durable record.
        PERFORM pg_notify('aml_alert_rejected', json_build_object('notice_id', nid, 'delivery_id', r.delivery_id,
                'business_date', (SELECT business_date FROM ${aml}.alert_delivery WHERE delivery_id = r.delivery_id),
                'alert_count', cardinality(r.ids), 'reason', left(p_reason, 400), 'rejected_by', session_user)::text);
        total := total + cardinality(r.ids);
    END LOOP;
    RETURN total;
END;
$$ LANGUAGE plpgsql;

-- Production remediation is recorded here: what was done, by whom, when.
CREATE FUNCTION ${aml}.resolve_rejection(p_notice_id BIGINT, p_action TEXT, p_note TEXT) RETURNS TEXT AS $$
DECLARE
    n ${aml}.alert_rejection_notice%ROWTYPE;
BEGIN
    IF p_action IS NULL OR p_action NOT IN ('FIXED_REREAD', 'HANDLED_MANUALLY') THEN
        RAISE EXCEPTION 'action must be FIXED_REREAD or HANDLED_MANUALLY';
    END IF;
    IF p_note IS NULL OR btrim(p_note) = '' THEN
        RAISE EXCEPTION 'a resolution note describing the remediation is required';
    END IF;
    SELECT * INTO n FROM ${aml}.alert_rejection_notice WHERE notice_id = p_notice_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown rejection notice %', p_notice_id;
    END IF;
    IF n.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'rejection notice % is already resolved (%)', p_notice_id, n.resolution_action;
    END IF;
    UPDATE ${aml}.alert_rejection_notice SET status = 'RESOLVED', resolution_action = p_action, resolution_note = left(p_note, 1000),
           resolved_by = session_user, resolved_ts = CURRENT_TIMESTAMP WHERE notice_id = p_notice_id;
    UPDATE ${aml}.alert_rejection SET resolved_ts = CURRENT_TIMESTAMP, resolved_by = session_user
     WHERE notice_id = p_notice_id AND resolved_ts IS NULL;
    RETURN p_action;
END;
$$ LANGUAGE plpgsql;

-- Confirmation compares the consumer's numbers with the control totals, less alerts handled manually (REQ-REJ-005).
CREATE OR REPLACE FUNCTION ${aml}.confirm_delivery(p_delivery_id BIGINT, p_received_count INTEGER, p_received_checksum TEXT) RETURNS TEXT AS $$
DECLARE
    d             ${aml}.alert_delivery%ROWTYPE;
    excl          INTEGER;
    eff_count     INTEGER;
    eff_checksum  TEXT;
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

    SELECT COUNT(DISTINCT r.alert_id) INTO excl
      FROM ${aml}.alert_rejection r JOIN ${aml}.alert_rejection_notice n ON n.notice_id = r.notice_id
     WHERE n.delivery_id = p_delivery_id AND n.resolution_action = 'HANDLED_MANUALLY';
    eff_count := d.alert_count - excl;
    IF excl = 0 THEN
        eff_checksum := d.checksum;
    ELSE
        eff_checksum := ${aml}.compute_alert_checksum(ARRAY(
            SELECT a.alert_id FROM ${aml}.alert a
             WHERE a.delivery_id = p_delivery_id
               AND NOT EXISTS (SELECT 1 FROM ${aml}.alert_rejection r JOIN ${aml}.alert_rejection_notice n ON n.notice_id = r.notice_id
                                WHERE r.alert_id = a.alert_id AND n.resolution_action = 'HANDLED_MANUALLY')));
    END IF;

    IF eff_count = p_received_count AND eff_checksum = lower(p_received_checksum) THEN
        UPDATE ${aml}.alert_delivery SET status = 'CONFIRMED', confirmed_ts = CURRENT_TIMESTAMP, confirmed_by = session_user,
               confirmation_channel = 'DB', received_count = p_received_count, received_checksum = lower(p_received_checksum),
               excluded_count = excl
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

CREATE VIEW ${aml}.v_alert_rejections AS
SELECT n.notice_id, n.delivery_id, d.business_date, d.revision, n.alert_count, n.reason, n.rejected_by, n.rejected_ts,
       n.status, n.resolution_action, n.resolution_note, n.resolved_by, n.resolved_ts,
       CASE WHEN n.status = 'OPEN' THEN round(CAST(extract(epoch FROM (CURRENT_TIMESTAMP - n.rejected_ts)) / 3600 AS numeric), 1) END AS hours_open
FROM ${aml}.alert_rejection_notice n
JOIN ${aml}.alert_delivery d ON d.delivery_id = n.delivery_id;

CREATE VIEW ${aml}.v_alert_rejection_items AS
SELECT r.notice_id, r.alert_id, a.rule_code, a.account_id, a.customer_id, a.business_date, r.reason, r.rejected_by, r.rejected_ts, r.resolved_ts
FROM ${aml}.alert_rejection r
JOIN ${aml}.alert a ON a.alert_id = r.alert_id;

CREATE OR REPLACE VIEW ${aml}.v_alert_reconciliation AS
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
            THEN round(CAST(extract(epoch FROM (CURRENT_TIMESTAMP - d.ready_ts)) / 3600 AS numeric), 1) END AS hours_unconfirmed,
       d.confirmation_channel, d.confirmation_reference,
       d.excluded_count
FROM ${aml}.alert_delivery d
WHERE d.status <> 'OPEN';
