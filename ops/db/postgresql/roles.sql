-- Database roles for the engine (ADR-0007). Run once as a DBA, after the schemas exist. Replace the passwords.
-- Three roles, because those are the separations that matter:
--   fisre_app   the engine. Owns the objects: partition swaps (DETACH/ATTACH) need ownership of mst.txn, so the engine
--               role is also the migration role. Do not give it to anyone else.
--   fisre_etl   the bank's loader. Registers batches and loads staging; cannot read master data or alerts.
--   case_mgmt   case management. Reads the alert view and acknowledges alerts (see case_mgmt_grants.sql).

CREATE ROLE fisre_app LOGIN PASSWORD 'change-me-app';
CREATE ROLE fisre_etl LOGIN PASSWORD 'change-me-etl';
CREATE ROLE case_mgmt LOGIN PASSWORD 'change-me-case';

ALTER SCHEMA stg OWNER TO fisre_app;
ALTER SCHEMA mst OWNER TO fisre_app;
ALTER SCHEMA aml OWNER TO fisre_app;

-- ETL: register a batch and mark it loaded, nothing else in aml; insert (and read back its own rows) in staging.
GRANT USAGE ON SCHEMA aml, stg TO fisre_etl;
GRANT SELECT (batch_id, batch_seq, business_date, status) ON aml.load_batch TO fisre_etl;
GRANT INSERT (batch_id, business_date, status) ON aml.load_batch TO fisre_etl;
GRANT UPDATE (status, loaded_ts) ON aml.load_batch TO fisre_etl;
GRANT INSERT, SELECT ON ALL TABLES IN SCHEMA stg TO fisre_etl;
-- Staging partitions are created by the engine owner when a batch is registered; give the ETL access to future ones too.
ALTER DEFAULT PRIVILEGES FOR ROLE fisre_app IN SCHEMA stg GRANT INSERT, SELECT ON TABLES TO fisre_etl;
GRANT USAGE ON SEQUENCE stg.stg_seq TO fisre_etl;

-- Real protection for handed-off alerts: the trigger is a safety net, privileges are the barrier.
REVOKE UPDATE, DELETE ON aml.alert FROM PUBLIC;
