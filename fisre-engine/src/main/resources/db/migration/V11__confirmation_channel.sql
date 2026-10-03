-- Confirmation can arrive through the database (function) or out of band (job or file); record how, and a reference.
ALTER TABLE ${aml}.alert_delivery ADD COLUMN confirmation_channel VARCHAR(20) CHECK (confirmation_channel IN ('DB', 'OPERATOR', 'FILE'));
ALTER TABLE ${aml}.alert_delivery ADD COLUMN confirmation_reference VARCHAR(200);

CREATE OR REPLACE FUNCTION ${aml}.confirm_delivery(p_delivery_id BIGINT, p_received_count INTEGER, p_received_checksum TEXT) RETURNS TEXT AS $$
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
               confirmation_channel = 'DB', received_count = p_received_count, received_checksum = lower(p_received_checksum)
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
       d.confirmation_channel, d.confirmation_reference
FROM ${aml}.alert_delivery d
WHERE d.status <> 'OPEN';
