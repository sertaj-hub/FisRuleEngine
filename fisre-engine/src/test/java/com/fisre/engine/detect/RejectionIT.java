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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Case management rejects alerts: notice, notification, audit, remediation, and closing the delivery (REQ-REJ-*). */
@SpringBootTest
class RejectionIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @Autowired DeliveryService deliveries;
    @TempDir Path tmp;

    Fixtures fx;
    long a1, a2, delivery1;

    @BeforeEach
    void publishedDeliveryWithTwoAlerts() throws IOException {
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.account("A2", "DEPOSIT", "C2", "2020-01-01");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "150", "2026-09-30");
        fx.txn("T2", "A2", "CASH_DEPOSIT", "CREDIT", "250", "2026-09-30");
        Files.writeString(tmp.resolve("T_RULE.yml"), "code: T_RULE\nname: Cash\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
        detection.detect(D);
        a1 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A1'", Long.class);
        a2 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A2'", Long.class);
        delivery1 = jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery WHERE revision = 1", Long.class);
    }

    int reject(String reason, long... ids) {
        String arr = java.util.Arrays.stream(ids).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return jdbc.queryForObject("SELECT aml.reject_alerts(ARRAY[" + arr + "]::bigint[], ?)", Integer.class, reason);
    }

    static String checksum(List<Long> ids) throws Exception {
        String joined = ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
    }

    String confirm(long delivery, int count, String checksum) {
        return jdbc.queryForObject("SELECT aml.confirm_delivery(?, ?, ?)", String.class, delivery, count, checksum);
    }

    long notice() {
        return jdbc.queryForObject("SELECT notice_id FROM aml.alert_rejection_notice ORDER BY notice_id DESC LIMIT 1", Long.class);
    }

    String me() {
        return jdbc.queryForObject("SELECT session_user", String.class);
    }

    @Test
    @Req("REQ-REJ-001")
    void aRejectionNeedsAReason_makesOneNoticePerDelivery_andIgnoresUnknownIds() {
        assertThatThrownBy(() -> reject(" ", a1)).hasMessageContaining("reason is required");
        assertThat(reject("whatever", 987654)).as("no known id: nothing recorded").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert_rejection_notice", Long.class)).isZero();

        fx.account("A3", "DEPOSIT", "C3", "2020-01-01");
        fx.txn("T3", "A3", "CASH_DEPOSIT", "CREDIT", "300", "2026-09-30");
        detection.detect(D);   // revision 2 with one more alert
        long a3 = jdbc.queryForObject("SELECT alert_id FROM aml.alert WHERE account_id = 'A3'", Long.class);

        assertThat(reject("customer unknown to case management", a1, a2, a3, 987654)).isEqualTo(3);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert_rejection_notice", Long.class)).as("one notice per delivery").isEqualTo(2);
        var first = jdbc.queryForMap("SELECT * FROM aml.alert_rejection_notice WHERE delivery_id = ?", delivery1);
        assertThat(first).containsEntry("alert_count", 2).containsEntry("reason", "customer unknown to case management")
                .containsEntry("rejected_by", me()).containsEntry("status", "OPEN");
        assertThat(first.get("rejected_ts")).isNotNull();
        assertThat(jdbc.queryForList("SELECT alert_id FROM aml.alert_rejection WHERE notice_id = ? ORDER BY 1", Long.class, first.get("notice_id")))
                .containsExactly(Math.min(a1, a2), Math.max(a1, a2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_export WHERE delivery_id = ?", Long.class, delivery1)).as("rejected alerts stay visible").isEqualTo(2);
    }

    @Test
    @Req("REQ-REJ-002")
    void aRejectionNotifiesListenersAtOnceWithTheReason() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("LISTEN aml_alert_rejected");
            PGConnection pg = c.unwrap(PGConnection.class);

            reject("bad customer id format", a1);

            PGNotification[] n = pg.getNotifications(5000);
            assertThat(n).hasSize(1);
            assertThat(n[0].getParameter()).contains("\"notice_id\" : " + notice()).contains("\"delivery_id\" : " + delivery1)
                    .contains("\"business_date\" : \"2026-10-01\"").contains("\"alert_count\" : 1")
                    .contains("\"reason\" : \"bad customer id format\"").contains("\"rejected_by\" : \"" + me() + "\"");
            c.createStatement().execute("UNLISTEN aml_alert_rejected");
        }
    }

    @Test
    @Req("REQ-REJ-003")
    void theAuditViewsShowWhoRejectedWhatWhyAndHowItWasRemediated() {
        reject("customer unknown", a1, a2);
        long n = notice();

        var open = jdbc.queryForMap("SELECT * FROM aml.v_alert_rejections WHERE notice_id = ?", n);
        assertThat(open).containsEntry("status", "OPEN").containsEntry("alert_count", 2).containsEntry("reason", "customer unknown")
                .containsEntry("rejected_by", me()).containsEntry("resolution_action", null);
        assertThat(open.get("business_date").toString()).isEqualTo("2026-10-01");
        assertThat(open.get("hours_open")).isNotNull();
        assertThat(jdbc.queryForList("SELECT account_id FROM aml.v_alert_rejection_items WHERE notice_id = ? ORDER BY 1", String.class, n)).containsExactly("A1", "A2");
        assertThat(jdbc.queryForObject("SELECT rule_code FROM aml.v_alert_rejection_items WHERE alert_id = ?", String.class, a1)).isEqualTo("T_RULE");

        jdbc.queryForObject("SELECT aml.resolve_rejection(?, 'FIXED_REREAD', 'loaded C1 and C2 into case management, ticket 12')", String.class, n);

        var done = jdbc.queryForMap("SELECT * FROM aml.v_alert_rejections WHERE notice_id = ?", n);
        assertThat(done).containsEntry("status", "RESOLVED").containsEntry("resolution_action", "FIXED_REREAD")
                .containsEntry("resolution_note", "loaded C1 and C2 into case management, ticket 12").containsEntry("resolved_by", me());
        assertThat(done.get("resolved_ts")).isNotNull();
        assertThat(done.get("hours_open")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_rejection_items WHERE notice_id = ? AND resolved_ts IS NOT NULL", Long.class, n)).isEqualTo(2);
    }

    @Test
    @Req("REQ-REJ-004")
    void resolvingNeedsAValidActionAndANote_andOnlyWorksOnce() {
        reject("customer unknown", a1);
        long n = notice();
        String call = "SELECT aml.resolve_rejection(?, ?, ?)";

        assertThatThrownBy(() -> jdbc.queryForObject(call, String.class, n, "IGNORE", "x")).hasMessageContaining("action must be FIXED_REREAD or HANDLED_MANUALLY");
        assertThatThrownBy(() -> jdbc.queryForObject(call, String.class, n, null, "x")).hasMessageContaining("action must be");
        assertThatThrownBy(() -> jdbc.queryForObject(call, String.class, n, "FIXED_REREAD", "  ")).hasMessageContaining("resolution note");
        assertThatThrownBy(() -> jdbc.queryForObject(call, String.class, 987654L, "FIXED_REREAD", "x")).hasMessageContaining("unknown rejection notice");
        assertThat(jdbc.queryForObject("SELECT status FROM aml.alert_rejection_notice WHERE notice_id = ?", String.class, n)).as("refused calls changed nothing").isEqualTo("OPEN");

        assertThat(deliveries.resolveRejection(n, "HANDLED_MANUALLY", "case opened by hand, ticket 77")).isEqualTo("HANDLED_MANUALLY");
        assertThatThrownBy(() -> deliveries.resolveRejection(n, "FIXED_REREAD", "again")).hasMessageContaining("already resolved (HANDLED_MANUALLY)");
        assertThat(jdbc.queryForObject("SELECT resolution_note FROM aml.alert_rejection_notice WHERE notice_id = ?", String.class, n)).isEqualTo("case opened by hand, ticket 77");
    }

    @Test
    @Req("REQ-REJ-005")
    void aDeliveryWithManuallyHandledAlertsCanBeClosed_whileAFixedRereadNeedsTheFullCount() throws Exception {
        // the consumer ingests only A1 and rejects A2
        reject("cannot ingest", a2);
        String onlyA1 = checksum(List.of(a1));
        String all = checksum(List.of(a1, a2));

        assertThat(confirm(delivery1, 1, onlyA1)).as("rejection still open").isEqualTo("MISMATCH");

        long n = notice();
        deliveries.resolveRejection(n, "FIXED_REREAD", "fixed the data");
        assertThat(confirm(delivery1, 1, onlyA1)).as("fixed means the consumer must re-read: the full count is expected").isEqualTo("MISMATCH");

        // ... but this one cannot be fixed: it is handled manually outside the intake
        reject("cannot ingest, closed account", a2);
        deliveries.resolveRejection(notice(), "HANDLED_MANUALLY", "analyst opened the case by hand, ticket 88");
        assertThat(confirm(delivery1, 2, all)).as("the full set no longer matches the effective totals").isEqualTo("MISMATCH");
        assertThat(confirm(delivery1, 1, onlyA1)).isEqualTo("CONFIRMED");

        var row = jdbc.queryForMap("SELECT * FROM aml.v_alert_reconciliation WHERE delivery_id = ?", delivery1);
        assertThat(row).containsEntry("status", "CONFIRMED").containsEntry("excluded_count", 1).containsEntry("expected_count", 2)
                .containsEntry("received_count", 1).containsEntry("pending_alerts", 0L).containsEntry("acknowledged_alerts", 2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_export", Long.class)).as("the manually handled alert leaves the view too").isZero();
        assertThat(confirm(delivery1, 1, onlyA1)).as("repeating is harmless").isEqualTo("CONFIRMED");
    }

    @Test
    @Req("REQ-REJ-005")
    void aDeliveryWithoutExceptionsStillNeedsTheFullCount() throws Exception {
        assertThat(confirm(delivery1, 1, checksum(List.of(a1)))).isEqualTo("MISMATCH");
        assertThat(confirm(delivery1, 2, checksum(List.of(a1, a2)))).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT excluded_count FROM aml.alert_delivery WHERE delivery_id = ?", Integer.class, delivery1)).isZero();
    }
}
