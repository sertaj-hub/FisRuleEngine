package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.promotion.BatchService;
import com.fisre.engine.rules.RuleLoader;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Audit columns, alert immutability and hostile input (REQ-AUD-001, REQ-AUD-002, REQ-SEC-003). */
@SpringBootTest
class AuditAndSecurityIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @Autowired BatchService batches;
    @TempDir Path tmp;

    Fixtures fx;

    @BeforeEach
    void reset() {
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
    }

    void alertForA1() throws IOException {
        fx.liveBatch("B1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "150", "2026-09-30");
        Files.writeString(tmp.resolve("T_RULE.yml"), "code: T_RULE\nname: Cash\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
        detection.detect(D);
    }

    String me() {
        return jdbc.queryForObject("SELECT session_user", String.class);
    }

    @Test
    @Req("REQ-AUD-001")
    void ruleVersionsBatchesAndAcknowledgementsRecordTheDatabaseUser() throws IOException {
        alertForA1();
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date) VALUES ('REG', DATE '2026-10-05')");
        long alertId = jdbc.queryForObject("SELECT alert_id FROM aml.alert", Long.class);
        assertThat(jdbc.queryForObject("SELECT handed_off_by FROM aml.alert", String.class)).isNull();

        jdbc.queryForObject("SELECT aml.ack_alerts(ARRAY[?]::bigint[])", Integer.class, alertId);

        assertThat(jdbc.queryForObject("SELECT created_by FROM aml.rule WHERE rule_code = 'T_RULE'", String.class)).isEqualTo(me());
        assertThat(jdbc.queryForObject("SELECT registered_by FROM aml.load_batch WHERE batch_id = 'REG'", String.class)).isEqualTo(me());
        assertThat(jdbc.queryForObject("SELECT handed_off_by FROM aml.alert", String.class)).isEqualTo(me());
    }

    @Test
    @Req("REQ-AUD-002")
    void aHandedOffAlertCannotBeChangedOrDeleted_andRerunsAddNoLinksToIt() throws IOException {
        alertForA1();
        long alertId = jdbc.queryForObject("SELECT alert_id FROM aml.alert", Long.class);
        jdbc.queryForObject("SELECT aml.ack_alerts(ARRAY[?]::bigint[])", Integer.class, alertId);

        assertThatThrownBy(() -> jdbc.update("UPDATE aml.alert SET summary = 'tampered' WHERE alert_id = ?", alertId)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE aml.alert SET handed_off_ts = NULL WHERE alert_id = ?", alertId)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM aml.alert WHERE alert_id = ?", alertId)).hasMessageContaining("immutable");
        assertThat(jdbc.queryForObject("SELECT summary FROM aml.alert WHERE alert_id = ?", String.class, alertId)).isEqualTo("Cash");

        // data changes after hand-off: a re-run must not add links to the alert case management already has
        long linksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert_txn WHERE alert_id = ?", Long.class, alertId);
        fx.txn("T2", "A1", "CASH_DEPOSIT", "CREDIT", "200", "2026-09-30");
        detection.detect(D);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert_txn WHERE alert_id = ?", Long.class, alertId)).isEqualTo(linksBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert", Long.class)).isEqualTo(1);

        // the documented purge bypass works, on that connection only
        fx.purgeAlerts();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert", Long.class)).isZero();
    }

    @Test
    @Req("REQ-AUD-002")
    void unsentAlertsStillReplaceOnRerun() throws IOException {
        alertForA1();
        assertThatCode(() -> detection.detect(D)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert", Long.class)).isEqualTo(1);
    }

    @Test
    @Req("REQ-SEC-003")
    void hostileTextInBatchIdsRuleNamesAndFilterValuesIsOnlyData() throws IOException {
        String evilBatch = "b'1\"; DROP TABLE mst.txn; --";
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status) VALUES (?, DATE '2026-10-01', 'LOADED')", evilBatch);
        jdbc.update("INSERT INTO stg.customer (batch_id, customer_id, customer_type, full_name) VALUES (?, 'C1', 'INDIVIDUAL', 'Robert''); DROP TABLE mst.customer;--')", evilBatch);
        jdbc.update("INSERT INTO stg.account (batch_id, account_id, primary_customer_id, product_type, status, open_date) VALUES (?, 'A1', 'C1', 'DEPOSIT', 'ACTIVE', DATE '2020-01-01')", evilBatch);
        jdbc.update("INSERT INTO stg.txn (batch_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                + " VALUES (?, 'T1''; DROP TABLE aml.alert;--', 'A1', TIMESTAMP '2026-09-30 10:00:00', DATE '2026-09-30', 'CASH_DEPOSIT', 'CREDIT', 500, 'USD')", evilBatch);

        assertThat(batches.promote(evilBatch).outcome()).isEqualTo(BatchService.Outcome.PROMOTED);

        Files.writeString(tmp.resolve("EVIL.yml"), "code: EVIL_RULE\nname: \"O'Brien\\\"; DROP TABLE aml.rule; --\"\ndescription: \"x'); DROP TABLE mst.account; --\"\n"
                + "template: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  filter: { txn_types: [\"X'); DROP TABLE mst.account; --\", CASH_DEPOSIT], channels: [\"c'; DROP TABLE mst.txn; --\"] }\n  min_sum: 100\n");
        loader.load(tmp);
        DetectionService.Result r = detection.detect(D);

        assertThat(r.rulesFailed()).isZero();
        for (String table : new String[] {"mst.txn", "mst.customer", "mst.account", "aml.alert", "aml.rule", "aml.load_batch"}) {
            assertThat(fx.exists(table)).as(table + " must still exist").isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mst.txn", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT transaction_id FROM mst.txn", String.class)).isEqualTo("T1'; DROP TABLE aml.alert;--");
        assertThat(jdbc.queryForObject("SELECT name FROM aml.rule WHERE rule_code = 'EVIL_RULE'", String.class)).isEqualTo("O'Brien\"; DROP TABLE aml.rule; --");
        assertThat(jdbc.queryForObject("SELECT full_name FROM mst.customer", String.class)).startsWith("Robert'); DROP TABLE");
    }
}
