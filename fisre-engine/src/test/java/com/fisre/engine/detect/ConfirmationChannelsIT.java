package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.rules.RuleLoader;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Availability hint (LISTEN/NOTIFY) and confirmation arriving outside the database (REQ-DLV-010, 011, 012). */
@SpringBootTest
class ConfirmationChannelsIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @Autowired DeliveryService deliveries;
    @TempDir Path tmp;

    @BeforeEach
    void alertsForTwoAccounts() throws IOException {
        Fixtures fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.account("A2", "DEPOSIT", "C2", "2020-01-01");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "150", "2026-09-30");
        fx.txn("T2", "A2", "CASH_DEPOSIT", "CREDIT", "250", "2026-09-30");
        Files.writeString(tmp.resolve("T_RULE.yml"), "code: T_RULE\nname: Cash\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
    }

    long delivery() {
        return jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery WHERE revision = 1", Long.class);
    }

    String checksum() {
        return jdbc.queryForObject("SELECT checksum FROM aml.alert_delivery WHERE delivery_id = ?", String.class, delivery());
    }

    Object cell(String column) {
        return jdbc.queryForMap("SELECT * FROM aml.v_alert_reconciliation WHERE delivery_id = ?", delivery()).get(column);
    }

    @Test
    @Req("REQ-DLV-010")
    void publishingSendsAHintToListeners_andANoOpRerunSendsNone() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("LISTEN aml_delivery_ready");
            PGConnection pg = c.unwrap(PGConnection.class);

            detection.detect(D);
            PGNotification[] first = pg.getNotifications(5000);

            assertThat(first).hasSize(1);
            assertThat(first[0].getName()).isEqualTo("aml_delivery_ready");
            assertThat(first[0].getParameter()).contains("\"delivery_id\" : " + delivery()).contains("\"business_date\" : \"2026-10-01\"")
                    .contains("\"revision\" : 1").contains("\"alert_count\" : 2");

            detection.detect(D);   // nothing new: no revision, so no hint
            PGNotification[] second = pg.getNotifications(1500);
            assertThat(second == null || second.length == 0).isTrue();
            c.createStatement().execute("UNLISTEN aml_delivery_ready");
        }
    }

    @Test
    @Req("REQ-DLV-011")
    void anOutOfBandConfirmationNeedsAReference_appliesTheSameComparison_andRecordsHowItArrived() {
        detection.detect(D);
        long d = delivery();

        assertThatThrownBy(() -> deliveries.confirmOutOfBand(d, 2, checksum(), "OPERATOR", " ")).hasMessageContaining("reference");
        assertThat(jdbc.queryForObject("SELECT status FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d)).as("nothing was applied").isEqualTo("READY");

        assertThat(deliveries.confirmOutOfBand(d, 1, checksum(), "OPERATOR", "TICKET-1")).isEqualTo("MISMATCH");
        assertThat(cell("confirmation_channel")).as("a mismatch is not a confirmation").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_export", Long.class)).isEqualTo(2);

        assertThat(deliveries.confirmOutOfBand(d, 2, checksum(), "OPERATOR", "TICKET-2")).isEqualTo("CONFIRMED");
        assertThat(cell("status")).isEqualTo("CONFIRMED");
        assertThat(cell("confirmation_channel")).isEqualTo("OPERATOR");
        assertThat(cell("confirmation_reference")).isEqualTo("TICKET-2");
        assertThat(cell("pending_alerts")).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_export", Long.class)).isZero();

        assertThat(deliveries.confirmOutOfBand(d, 2, checksum(), "FILE", "other.csv")).as("repeating is harmless").isEqualTo("CONFIRMED");
        assertThat(cell("confirmation_reference")).as("the first confirmation is kept").isEqualTo("TICKET-2");
    }

    @Test
    @Req("REQ-DLV-012")
    void aConfirmationFileIsAppliedLineByLine_andOneBadLineDoesNotStopTheRest() throws IOException {
        detection.detect(D);
        long d = delivery();
        fx().account("A3", "DEPOSIT", "C3", "2020-01-01");
        fx().txn("T3", "A3", "CASH_DEPOSIT", "CREDIT", "300", "2026-09-30");
        detection.detect(D);   // revision 2
        long d2 = jdbc.queryForObject("SELECT delivery_id FROM aml.alert_delivery WHERE revision = 2", Long.class);
        String sum2 = jdbc.queryForObject("SELECT checksum FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d2);
        Path file = tmp.resolve("case-mgmt-confirmations.csv");
        Files.writeString(file, String.join("\n",
                "delivery_id,received_count,received_checksum,reference",
                d + ",2," + checksum() + ",MSG-77",
                "",
                d2 + ",5," + sum2,                    // wrong count, no reference
                "999999,1,abc,MSG-78",                // unknown delivery
                "not a line"));

        List<DeliveryService.ConfirmationResult> r = deliveries.importConfirmations(file);

        assertThat(r).extracting(DeliveryService.ConfirmationResult::outcome).containsExactly("CONFIRMED", "MISMATCH", "ERROR", "ERROR");
        assertThat(r.get(2).message()).contains("unknown delivery");
        assertThat(r.get(3).message()).contains("expected delivery_id");
        assertThat(jdbc.queryForObject("SELECT confirmation_channel || ':' || confirmation_reference FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d))
                .isEqualTo("FILE:MSG-77");
        assertThat(jdbc.queryForObject("SELECT status FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d2)).isEqualTo("MISMATCH");

        // a line without a reference uses the file name
        Path file2 = tmp.resolve("second.csv");
        Files.writeString(file2, d2 + ",1," + sum2 + "\n");
        assertThat(deliveries.importConfirmations(file2)).extracting(DeliveryService.ConfirmationResult::outcome).containsExactly("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT confirmation_reference FROM aml.alert_delivery WHERE delivery_id = ?", String.class, d2)).isEqualTo("second.csv");
        assertThatThrownBy(() -> deliveries.importConfirmations(tmp.resolve("missing.csv"))).hasMessageContaining("Cannot read confirmation file");
    }

    private Fixtures fx() {
        return new Fixtures(jdbc, props);
    }
}
