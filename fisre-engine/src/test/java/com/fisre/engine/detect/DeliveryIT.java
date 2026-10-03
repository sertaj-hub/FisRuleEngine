package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.rules.RuleLoader;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The reconciled outbox between the engine and case management (specs/requirements/delivery.md). */
@SpringBootTest
class DeliveryIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");   // business date; posting day 2026-09-30

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @TempDir Path tmp;

    Fixtures fx;

    @BeforeEach
    void twoCashAccounts() throws IOException {
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.account("A2", "DEPOSIT", "C2", "2020-01-01");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "150", "2026-09-30");
        fx.txn("T2", "A2", "CASH_DEPOSIT", "CREDIT", "250", "2026-09-30");
        Files.writeString(tmp.resolve("T_RULE.yml"), "code: T_RULE\nname: Cash\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
    }

    // ---- helpers --------------------------------------------------------------------------------

    void breakARule() {
        jdbc.update("INSERT INTO aml.rule (rule_code, version, name, template_code, status, config) VALUES ('A_BROKEN', 1, 'Broken', 'NO_SUCH_TEMPLATE', 'ACTIVE', CAST('{}' AS jsonb))");
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    List<Long> ids(long delivery) {
        return jdbc.queryForList("SELECT alert_id FROM aml.alert WHERE delivery_id = ? ORDER BY alert_id", Long.class, delivery);
    }

    /** The documented formula, implemented independently of the database function. */
    static String checksum(List<Long> ids) throws Exception {
        String joined = ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
    }

    long delivery(int revision) {
        return jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery WHERE business_date = DATE '2026-10-01' AND revision = ?", Long.class, revision);
    }

    String confirm(long delivery, long count, String checksum) {
        return jdbc.queryForObject("SELECT aml.confirm_delivery(?, ?, ?)", String.class, delivery, (int) count, checksum);
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    @Req({"REQ-DLV-001", "REQ-DLV-003"})
    void aRunPublishesOneDeliveryWithControlTotals_andADateWithNoAlertsIsStillPublished() throws Exception {
        detection.detect(D);

        long d1 = delivery(1);
        assertThat(jdbc.queryForObject("SELECT status FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d1)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT alert_count FROM aml.alert_delivery WHERE delivery_id = ?", Integer.class, d1)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT CAST(rule_counts AS text) FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d1)).isEqualTo("{\"T_RULE\": 2}");
        assertThat(jdbc.queryForObject("SELECT checksum FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d1)).isEqualTo(checksum(ids(d1)));
        assertThat(jdbc.queryForObject("SELECT aml.compute_alert_checksum(?::bigint[])", String.class, "{" + ids(d1).stream().map(String::valueOf).collect(Collectors.joining(",")) + "}"))
                .as("the function and the documented formula agree").isEqualTo(checksum(ids(d1)));
        assertThat(jdbc.queryForObject("SELECT ready_ts IS NOT NULL FROM aml.alert_delivery WHERE delivery_id = ?", Boolean.class, d1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_delivery", Long.class)).isEqualTo(1);

        fx.liveBatch("B2", "2026-10-02");   // nothing happened on posting day 2026-10-01
        detection.detect(LocalDate.parse("2026-10-02"));
        assertThat(jdbc.queryForObject("SELECT status || ':' || alert_count || ':' || checksum FROM aml.alert_delivery WHERE business_date = DATE '2026-10-02'", String.class))
                .isEqualTo("READY:0:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");   // SHA-256 of the empty string
    }

    @Test
    @Req("REQ-DLV-002")
    void theViewShowsOnlyUnsentAlertsOfPublishedDeliveries() {
        detection.detect(D);
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).isEqualTo(2);

        jdbc.update("UPDATE aml.alert_delivery SET status = 'OPEN'");
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).as("an OPEN delivery is invisible").isZero();
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_delivery")).isZero();

        jdbc.update("UPDATE aml.alert_delivery SET status = 'MISMATCH'");
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).as("a MISMATCH delivery can be read again").isEqualTo(2);

        jdbc.queryForObject("SELECT aml.ack_alerts(ARRAY(SELECT alert_id FROM aml.alert LIMIT 1))", Integer.class);
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).as("acknowledged alerts leave the view").isEqualTo(1);
    }

    @Test
    @Req("REQ-DLV-004")
    void confirmationComparesTheConsumersNumbersWithTheControlTotals() throws Exception {
        detection.detect(D);
        long d1 = delivery(1);
        String good = checksum(ids(d1));

        assertThat(confirm(d1, 1, good)).as("one alert short").isEqualTo("MISMATCH");
        assertThat(jdbc.queryForObject("SELECT status || ':' || received_count FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d1)).isEqualTo("MISMATCH:1");
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).as("nothing is marked handed off on a mismatch").isEqualTo(2);
        assertThat(confirm(d1, 2, "0".repeat(64))).as("right count, wrong content").isEqualTo("MISMATCH");

        assertThat(confirm(d1, 2, good.toUpperCase())).as("checksum compares case-insensitively").isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status || ':' || confirmed_by FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d1))
                .isEqualTo("CONFIRMED:" + jdbc.queryForObject("SELECT session_user", String.class));
        assertThat(count("SELECT COUNT(*) FROM aml.alert WHERE delivery_id = ? AND handed_off_ts IS NOT NULL", d1)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).isZero();

        assertThat(confirm(d1, 2, good)).as("repeating a matching confirmation is harmless").isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> confirm(d1, 5, good)).hasMessageContaining("already CONFIRMED with different numbers");
        assertThatThrownBy(() -> confirm(999999, 0, "x")).hasMessageContaining("unknown delivery");
        jdbc.update("INSERT INTO aml.alert_delivery (business_date, revision, status) VALUES (DATE '2026-10-05', 1, 'OPEN')");
        long open = jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery WHERE status = 'OPEN'", Long.class);
        assertThatThrownBy(() -> confirm(open, 0, "x")).hasMessageContaining("not published yet");
    }

    @Test
    @Req("REQ-DLV-005")
    void rejectionsAreRecordedWithReasonAndUser_andNothingIsDeleted() {
        detection.detect(D);
        long a1 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A1'", Long.class);

        Integer n = jdbc.queryForObject("SELECT aml.reject_alerts(ARRAY[?, 987654]::bigint[], 'customer C1 unknown to case management')", Integer.class, a1);

        assertThat(n).as("the unknown id is ignored").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason || '|' || rejected_by || '|' || (resolved_ts IS NULL) FROM aml.alert_rejection", String.class))
                .isEqualTo("customer C1 unknown to case management|" + jdbc.queryForObject("SELECT session_user", String.class) + "|true");
        assertThat(count("SELECT COUNT(*) FROM aml.alert")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).as("a rejected alert stays visible").isEqualTo(2);
    }

    @Test
    @Req("REQ-DLV-006")
    void aPublishedDeliveryIsFrozen_newHitsFormTheNextRevision_andANoOpRerunCreatesNone() throws Exception {
        detection.detect(D);
        long d1 = delivery(1);
        List<Long> before = ids(d1);
        String checksumBefore = jdbc.queryForObject("SELECT checksum FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d1);

        detection.detect(D);   // nothing changed
        assertThat(count("SELECT COUNT(*) FROM aml.alert_delivery")).as("no empty revision").isEqualTo(1);

        fx.account("A3", "DEPOSIT", "C3", "2020-01-01");
        fx.txn("T3", "A3", "CASH_DEPOSIT", "CREDIT", "300", "2026-09-30");
        fx.txn("T4", "A1", "CASH_DEPOSIT", "CREDIT", "400", "2026-09-30");   // more evidence for an alert already published
        detection.detect(D);

        assertThat(count("SELECT COUNT(*) FROM aml.alert_delivery")).isEqualTo(2);
        assertThat(ids(d1)).as("revision 1 alerts unchanged").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT checksum FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d1)).isEqualTo(checksumBefore);
        assertThat(count("SELECT COUNT(*) FROM aml.alert_txn WHERE alert_id = ?", before.get(0))).as("no links added to a published alert").isEqualTo(1);
        long d2 = delivery(2);
        assertThat(jdbc.queryForObject("SELECT status || ':' || alert_count FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d2)).isEqualTo("READY:1");
        assertThat(jdbc.queryForObject("SELECT account_id FROM aml.alert WHERE delivery_id = ?", String.class, d2)).isEqualTo("A3");
        assertThat(jdbc.queryForObject("SELECT checksum FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d2)).isEqualTo(checksum(ids(d2)));
    }

    @Test
    @Req("REQ-DLV-007")
    void anAlertThatNoLongerHitsIsReportedAsWithdrawn_notChanged() {
        detection.detect(D);
        long a2 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A2'", Long.class);
        long a1 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A1'", Long.class);
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_events")).isZero();

        jdbc.update("DELETE FROM mst.txn WHERE transaction_id = 'T2'");   // corrected data: A2 no longer hits
        detection.detect(D);
        detection.detect(D);   // and again: the event is not repeated

        assertThat(jdbc.queryForList("SELECT alert_id FROM aml.v_alert_events", Long.class)).containsExactly(a2);
        assertThat(jdbc.queryForObject("SELECT event_type || '|' || rule_code || '|' || account_id FROM aml.v_alert_events", String.class)).isEqualTo("WITHDRAWN|T_RULE|A2");
        assertThat(jdbc.queryForObject("SELECT reason FROM aml.v_alert_events", String.class)).contains("2026-10-01");
        assertThat(count("SELECT COUNT(*) FROM aml.alert WHERE alert_id IN (?, ?)", a1, a2)).as("the alerts themselves are untouched").isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM aml.alert_delivery")).as("withdrawals do not create revisions").isEqualTo(1);
    }

    @Test
    @Req("REQ-DLV-008")
    void aFailedRuleKeepsTheDeliveryOpenAndInvisible_untilARunSucceeds() {
        breakARule();

        DetectionService.Result r = detection.detect(D);

        assertThat(r.rulesFailed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM aml.alert_delivery", String.class)).isEqualTo("OPEN");
        assertThat(count("SELECT COUNT(*) FROM aml.alert")).as("the good rule's alerts exist but are hidden").isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).isZero();
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_delivery")).isZero();

        jdbc.update("UPDATE aml.rule SET status = 'RETIRED' WHERE rule_code = 'A_BROKEN'");
        assertThat(detection.detect(D).rulesFailed()).isZero();

        assertThat(jdbc.queryForObject("SELECT revision || ':' || status || ':' || alert_count FROM aml.alert_delivery", String.class)).as("the same delivery is published").isEqualTo("1:READY:2");
        assertThat(count("SELECT COUNT(*) FROM aml.v_alert_export")).isEqualTo(2);
    }

    @Test
    @Req("REQ-DLV-009")
    void theReconciliationViewShowsWhereEveryAlertStands() throws Exception {
        detection.detect(D);
        long d1 = delivery(1);
        long a1 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A1'", Long.class);
        long a2 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A2'", Long.class);
        jdbc.queryForObject("SELECT aml.ack_alerts(ARRAY[?]::bigint[])", Integer.class, a1);
        jdbc.queryForObject("SELECT aml.reject_alerts(ARRAY[?]::bigint[], 'bad data')", Integer.class, a2);

        var row = jdbc.queryForMap("SELECT * FROM aml.v_alert_reconciliation WHERE delivery_id = ?", d1);

        assertThat(row).containsEntry("status", "READY").containsEntry("expected_count", 2).containsEntry("created_alerts", 2L)
                .containsEntry("acknowledged_alerts", 1L).containsEntry("pending_alerts", 1L).containsEntry("rejected_unresolved", 1L)
                .containsEntry("withdrawn_alerts", 0L).containsEntry("received_count", null);
        assertThat(row.get("checksum")).isEqualTo(checksum(ids(d1)));
        assertThat(row.get("hours_unconfirmed")).isNotNull();

        confirm(d1, 2, checksum(ids(d1)));
        var done = jdbc.queryForMap("SELECT * FROM aml.v_alert_reconciliation WHERE delivery_id = ?", d1);
        assertThat(done).containsEntry("status", "CONFIRMED").containsEntry("pending_alerts", 0L).containsEntry("acknowledged_alerts", 2L).containsEntry("received_count", 2);
        assertThat(done.get("hours_unconfirmed")).isNull();
    }
}
