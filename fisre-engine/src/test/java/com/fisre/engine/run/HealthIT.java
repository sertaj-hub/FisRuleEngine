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

    void delivery(String date, String status, String readyAgo) {
        jdbc.update("INSERT INTO aml.alert_delivery (business_date, revision, status, ready_ts, alert_count) VALUES (?::date, 1, ?, CURRENT_TIMESTAMP - ?::interval, 0)",
                date, status, readyAgo);
    }

    void rule() {
        jdbc.update("INSERT INTO aml.rule (rule_code, version, name, template_code, status, config) VALUES ('R1', 1, 'R', 'AGGREGATE', 'ACTIVE', CAST('{}' AS jsonb))");
    }

    @Test
    @Req("REQ-HLT-002")
    void aHealthySystemReportsNothing() {
        assertThat(health.check(Optional.empty())).isEmpty();
        fx.liveBatch("B1", "2026-10-01");
        delivery("2026-10-01", "CONFIRMED", "1 hour");
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
        assertThat(findings(Optional.of(D))).containsExactly("CRITICAL:NIGHTLY_INCOMPLETE", "CRITICAL:DELIVERY_NOT_PUBLISHED");
        assertThat(findings(Optional.empty())).as("without a date the nightly check is not made").isEmpty();

        jdbc.update("INSERT INTO aml.nightly_run (business_date, batch_id, step, status) VALUES (DATE '2026-10-01', 'B1', 'PROMOTE', 'SUCCESS'), (DATE '2026-10-01', 'B1', 'DETECT', 'FAILED')");
        assertThat(health.check(Optional.of(D)).get(0).message()).contains("promote: SUCCESS").contains("detect: FAILED");

        jdbc.update("INSERT INTO aml.nightly_run (business_date, batch_id, step, status) VALUES (DATE '2026-10-01', 'B1', 'PROMOTE', 'SKIPPED'), (DATE '2026-10-01', 'B1', 'DETECT', 'SUCCESS')");
        delivery("2026-10-01", "READY", "1 hour");
        assertThat(findings(Optional.of(D))).as("the retry completed and its delivery is published").isEmpty();
    }

    @Test
    @Req("REQ-HLT-001")
    void unacknowledgedAlertsOlderThanTheLimitAreAWarning() {
        rule();
        long rule = jdbc.queryForObject("SELECT rule_id FROM aml.rule", Long.class);
        delivery("2026-10-01", "READY", "1 hour");
        long delivery = jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery", Long.class);
        String insert = "INSERT INTO aml.alert (rule_id, rule_code, rule_version, business_date, account_id, customer_id, product_type, summary, evidence, created_ts, delivery_id)"
                + " VALUES (?, 'R1', 1, DATE '2026-10-01', ?, 'C1', 'DEPOSIT', 's', CAST('{}' AS jsonb), CURRENT_TIMESTAMP - make_interval(hours => ?), " + delivery + ")";
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
        delivery(java.time.LocalDate.now().toString(), "READY", "1 hour");
        long delivery = jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery", Long.class);
        jdbc.update("INSERT INTO aml.alert (rule_id, rule_code, rule_version, business_date, account_id, customer_id, product_type, summary, evidence, delivery_id)"
                + " VALUES (?, 'R1', 1, CURRENT_DATE, 'A1', 'C1', 'DEPOSIT', 's', CAST('{}' AS jsonb), " + delivery + "), (?, 'R1', 1, CURRENT_DATE, 'A2', 'C2', 'DEPOSIT', 's', CAST('{}' AS jsonb), " + delivery + ")", rule, rule);

        assertThat(jdbc.queryForList("SELECT batch_id FROM aml.v_ops_batches ORDER BY 1", String.class)).as("last 45 days only").containsExactly("BAD");
        assertThat(jdbc.queryForObject("SELECT txn_rejected FROM aml.v_ops_batches WHERE batch_id = 'BAD'", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT kind FROM aml.v_ops_failures ORDER BY 1", String.class)).containsExactly("BATCH", "NIGHTLY", "RULE");
        assertThat(jdbc.queryForObject("SELECT unsent_alerts FROM aml.v_ops_alert_backlog", Long.class)).isEqualTo(2);
    }

    @Test
    @Req("REQ-HLT-004")
    void deliveryProblemsAreReported() {
        rule();
        long rule = jdbc.queryForObject("SELECT rule_id FROM aml.rule", Long.class);

        delivery("2026-10-01", "READY", "2 hours");
        assertThat(findings(Optional.empty())).as("published recently, still within the confirmation window").isEmpty();

        jdbc.update("UPDATE aml.alert_delivery SET ready_ts = CURRENT_TIMESTAMP - INTERVAL '20 hours'");
        assertThat(findings(Optional.empty())).containsExactly("CRITICAL:DELIVERY_NOT_CONFIRMED");

        jdbc.update("UPDATE aml.alert_delivery SET status = 'MISMATCH'");
        assertThat(findings(Optional.empty())).containsExactly("CRITICAL:DELIVERY_MISMATCH");

        jdbc.update("UPDATE aml.alert_delivery SET status = 'CONFIRMED'");
        assertThat(findings(Optional.empty())).isEmpty();
        assertThat(findings(Optional.of(D))).as("this date has a published delivery").doesNotContain("CRITICAL:DELIVERY_NOT_PUBLISHED");
        assertThat(findings(Optional.of(LocalDate.parse("2026-10-09")))).contains("CRITICAL:DELIVERY_NOT_PUBLISHED");

        long delivery = jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery", Long.class);
        jdbc.update("INSERT INTO aml.alert (rule_id, rule_code, rule_version, business_date, account_id, customer_id, product_type, summary, evidence, delivery_id)"
                + " VALUES (?, 'R1', 1, DATE '2026-10-01', 'A1', 'C1', 'DEPOSIT', 's', CAST('{}' AS jsonb), ?)", rule, delivery);
        long alert = jdbc.queryForObject("SELECT alert_id FROM aml.alert", Long.class);
        jdbc.queryForObject("SELECT aml.reject_alerts(ARRAY[?]::bigint[], 'cannot parse')", Integer.class, alert);
        assertThat(findings(Optional.empty()).stream().filter(f -> f.contains("ALERT_REJECTED")).toList()).containsExactly("WARN:ALERT_REJECTED");

        jdbc.update("UPDATE aml.alert_rejection SET resolved_ts = CURRENT_TIMESTAMP, resolved_by = 'ops'");
        assertThat(findings(Optional.empty())).isEmpty();
    }
}
