-- Rule configuration UI (ADR-0010): approval workflow, audit trail, users.
ALTER TABLE ${aml}.rule DROP CONSTRAINT rule_status_check;
ALTER TABLE ${aml}.rule ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE ${aml}.rule ADD CONSTRAINT rule_status_check
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'REJECTED', 'ACTIVE', 'RETIRED'));
ALTER TABLE ${aml}.rule
    ADD COLUMN source          VARCHAR(10) NOT NULL DEFAULT 'YAML' CHECK (source IN ('YAML', 'UI')),
    ADD COLUMN authored_by     VARCHAR(100),
    ADD COLUMN change_reason   VARCHAR(1000),
    ADD COLUMN submitted_by    VARCHAR(100),
    ADD COLUMN submitted_ts    TIMESTAMP,
    ADD COLUMN decided_by      VARCHAR(100),
    ADD COLUMN decided_ts      TIMESTAMP,
    ADD COLUMN decision_note   VARCHAR(1000);
-- four-eyes: whoever submitted a version can never be the one who decided it
ALTER TABLE ${aml}.rule ADD CONSTRAINT ck_rule_four_eyes CHECK (decided_by IS NULL OR submitted_by IS NULL OR decided_by <> submitted_by);

CREATE TABLE ${aml}.rule_audit (
    audit_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_code  VARCHAR(60) NOT NULL,
    version    INTEGER,
    action     VARCHAR(20) NOT NULL,
    actor      VARCHAR(100) NOT NULL,
    audit_ts   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    detail     JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_rule_audit_rule ON ${aml}.rule_audit (rule_code, audit_id);

CREATE FUNCTION ${aml}.protect_rule_audit() RETURNS trigger AS $$
BEGIN
    IF current_setting('aml.allow_audit_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'aml.rule_audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_rule_audit_immutable BEFORE UPDATE OR DELETE ON ${aml}.rule_audit
    FOR EACH ROW EXECUTE FUNCTION ${aml}.protect_rule_audit();

CREATE TABLE ${aml}.rule_user (
    username      VARCHAR(100) PRIMARY KEY,
    password_hash VARCHAR(200) NOT NULL,
    roles         VARCHAR(100) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_ts    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
