package com.fisre.engine.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.spec.Req;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class HealthIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired HealthService health;

    Fixtures fx;

    @BeforeEach
    void reset() {
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
    }

    List<String> findings(Optional<LocalDate> date) {
        return health.check(date).stream().map(f -> f.severity() + ":" + f.check()).toList();
    }

    void rule() {
        jdbc.update("INSERT INTO aml.rule (rule_code, version, name, template_code, status, config) VALUES ('R1', 1, 'R', 'AGGREGATE', 'ACTIVE', CAST('{}' AS jsonb))");
    }

    @Test
    @Req("REQ-HLT-002")
    void aHealthySystemReportsNothing() {
        assertThat(health.check(Optional.empty())).isEmpty();
        fx.liveBatch("B1", "2026-10-01");
        jdbc.update("INSERT INTO aml.nightly_run (business_date, batch_id, step, status) VALUES (DATE '2026-10-01', 'B1', 'PROMOTE', 'SUCCESS'), (DATE '2026-10-01', 'B1', 'DETECT', 'SUCCESS'), (DATE '2026-10-01', 'B1', 'RETAIN', 'SUCCESS')");
        fx.ensurePartition("2026-09-29");
        fx.ensurePartition("2026-09-30");
        assertThat(health.check(Optional.of(D))).isEmpty();
    }

    @Test
    @Req("REQ-HLT-001")
    void aBatchStuckInPromotingIsCritical_onlyAfterTheThreshold() {
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status, claimed_ts) VALUES ('FRESH', DATE '2026-10-01', 'PROMOTING', CURRENT_TIMESTAMP)");
        assertThat(findings(Optional.empty())).isEmpty();

        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status, claimed_ts) VALUES ('STUCK', DATE '2026-10-02', 'PROMOTING', CURRENT_TIMESTAMP - INTERVAL '3 hours')");

        assertThat(findings(Optional.empty())).containsExactly("CRITICAL:STUCK_BATCH");
        assertThat(health.check(Optional.empty()).get(0).message()).contains("STUCK").doesNotContain("FRESH");
    }

    @Test
    @Req("REQ-HLT-001")
    void aRuleFailureIsCriticalUntilALaterRunSucceeds() {
        rule();
        long rule = jdbc.queryForObject("SELECT rule_id FROM aml.rule", Long.class);
        jdbc.update("INSERT INTO aml.rule_run (rule_id, rule_code, business_date, status) VALUES (?, 'R1', DATE '2026-10-01', 'FAILED')", rule);
        assertThat(findings(Optional.empty())).containsExactly("CRITICAL:RULE_FAILED");

        jdbc.update("INSERT INTO aml.rule_run (rule_id, rule_code, business_date, status) VALUES (?, 'R1', DATE '2026-10-01', 'SUCCESS')", rule);
        assertThat(findings(Optional.empty())).isEmpty();
    }

    @Test
    @Req("REQ-HLT-001")
    void anIncompleteNightlyRunIsCriticalForTheGivenDate() {
        assertThat(findings(Optional.of(D))).containsExactly("CRITICAL:NIGHTLY_INCOMPLETE");
        assertThat(findings(Optional.empty())).as("without a date the nightly check is not made").isEmpty();

        jdbc.update("INSERT INTO aml.nightly_run (business_date, batch_id, step, status) VALUES (DATE '2026-10-01', 'B1', 'PROMOTE', 'SUCCESS'), (DATE '2026-10-01', 'B1', 'DETECT', 'FAILED')");
        assertThat(health.check(Optional.of(D)).get(0).message()).contains("promote: SUCCESS").contains("detect: FAILED");

        jdbc.update("INSERT INTO aml.nightly_run (business_date, batch_id, step, status) VALUES (DATE '2026-10-01', 'B1', 'PROMOTE', 'SKIPPED'), (DATE '2026-10-01', 'B1', 'DETECT', 'SUCCESS')");
        assertThat(findings(Optional.of(D))).as("the retry completed").isEmpty();
    }

    @Test
    @Req("REQ-HLT-001")
    void unacknowledgedAlertsOlderThanTheLimitAreAWarning() {
        rule();
        long rule = jdbc.queryForObject("SELECT rule_id FROM aml.rule", Long.class);
        String insert = "INSERT INTO aml.alert (rule_id, rule_code, rule_version, business_date, account_id, customer_id, product_type, summary, evidence, created_ts)"
                + " VALUES (?, 'R1', 1, DATE '2026-10-01', ?, 'C1', 'DEPOSIT', 's', CAST('{}' AS jsonb), CURRENT_TIMESTAMP - make_interval(hours => ?))";
        jdbc.update(insert, rule, "NEW1", 2);
        assertThat(findings(Optional.empty())).as("a fresh alert is not a backlog").isEmpty();

        jdbc.update(insert, rule, "OLD1", 30);
        assertThat(findings(Optional.empty())).containsExactly("WARN:ALERT_BACKLOG");

        jdbc.queryForObject("SELECT aml.ack_alerts(ARRAY(SELECT alert_id FROM aml.alert))", Integer.class);
        assertThat(findings(Optional.empty())).isEmpty();
    }

    @Test
    @Req("REQ-HLT-001")
    void missingPostingDaysInsideTheLoadedRangeAreAWarning() {
        fx.ensurePartition("2026-09-28");
        fx.ensurePartition("2026-09-30");
        List<HealthService.Finding> f = health.check(Optional.empty());
        assertThat(f).extracting(HealthService.Finding::check).containsExactly("MISSING_POSTING_DAYS");
        assertThat(f.get(0).severity()).isEqualTo("WARN");
        assertThat(f.get(0).message()).contains("2026-09-29");

        fx.ensurePartition("2026-09-29");
        assertThat(findings(Optional.empty())).isEmpty();
    }

    @Test
    @Req("REQ-HLT-001")
    void aLeftoverBuildTableIsAWarning() {
        jdbc.execute("CREATE TABLE mst.txn_new_9999 (x int)");
        try {
            assertThat(findings(Optional.empty())).containsExactly("WARN:LEFTOVER_BUILD_TABLE");
        } finally {
            jdbc.execute("DROP TABLE mst.txn_new_9999");
        }
    }

    @Test
    @Req("REQ-HLT-003")
    void operationsViewsShowRecentBatchesFailuresAndTheBacklog() {
        rule();
        long rule = jdbc.queryForObject("SELECT rule_id FROM aml.rule", Long.class);
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status, error_msg) VALUES ('BAD', CURRENT_DATE - 1, 'FAILED', '3 staged row(s) failed validation')");
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status) VALUES ('OLD', CURRENT_DATE - 100, 'CLEANED')");
        jdbc.update("INSERT INTO aml.load_batch_entity (batch_id, entity, staged_cnt, rejected_cnt, promoted_cnt) VALUES ('BAD', 'TXN', 10, 3, 0)");
        jdbc.update("INSERT INTO aml.rule_run (rule_id, rule_code, business_date, status, error_msg) VALUES (?, 'R1', CURRENT_DATE, 'FAILED', 'boom')", rule);
        jdbc.update("INSERT INTO aml.nightly_run (business_date, batch_id, step, status, message) VALUES (CURRENT_DATE, 'BAD', 'PROMOTE', 'FAILED', 'validation')");
        jdbc.update("INSERT INTO aml.alert (rule_id, rule_code, rule_version, business_date, account_id, customer_id, product_type, summary, evidence)"
                + " VALUES (?, 'R1', 1, CURRENT_DATE, 'A1', 'C1', 'DEPOSIT', 's', CAST('{}' AS jsonb)), (?, 'R1', 1, CURRENT_DATE, 'A2', 'C2', 'DEPOSIT', 's', CAST('{}' AS jsonb))", rule, rule);

        assertThat(jdbc.queryForList("SELECT batch_id FROM aml.v_ops_batches ORDER BY 1", String.class)).as("last 45 days only").containsExactly("BAD");
        assertThat(jdbc.queryForObject("SELECT txn_rejected FROM aml.v_ops_batches WHERE batch_id = 'BAD'", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT kind FROM aml.v_ops_failures ORDER BY 1", String.class)).containsExactly("BATCH", "NIGHTLY", "RULE");
        assertThat(jdbc.queryForObject("SELECT unsent_alerts FROM aml.v_ops_alert_backlog", Long.class)).isEqualTo(2);
    }
}
