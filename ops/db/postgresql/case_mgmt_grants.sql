-- Run once as a DBA. Gives the case management system's database user read-only access to the alert contract:
-- the export view and the acknowledge function, nothing else. Replace case_mgmt with its login role.
GRANT USAGE ON SCHEMA aml TO case_mgmt;
GRANT SELECT ON aml.v_alert_export TO case_mgmt;
GRANT EXECUTE ON FUNCTION aml.ack_alerts(BIGINT[]) TO case_mgmt;
-- The view reads aml.alert, aml.rule, aml.alert_txn and mst.txn with the view owner's rights, so the consumer needs no access to them.
-- ack_alerts updates aml.alert; create it SECURITY DEFINER if the owner differs from the consumer:
--   ALTER FUNCTION aml.ack_alerts(BIGINT[]) SECURITY DEFINER SET search_path = aml, pg_temp;
