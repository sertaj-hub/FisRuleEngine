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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DetectionIT {

    static final LocalDate D = LocalDate.parse("2026-09-30");

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @TempDir Path tmp;

    String aml;
    Fixtures fx;

    @BeforeEach
    void reset() {
        aml = props.schemas().aml();
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
    }

    /** One cash-over-$100-a-day rule. */
    void loadRule(int suppressDays) throws IOException {
        Files.writeString(tmp.resolve("T_RULE.yml"), "code: T_RULE\nname: Cash over $100 in a day\ntemplate: AGGREGATE\nstatus: ACTIVE\nsuppress_days: "
                + suppressDays + "\nconfig:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
    }

    void cashDay(String txnId, String date, String amount) {
        fx.txn(txnId, "A1", "CASH_DEPOSIT", "CREDIT", amount, date);
    }

    long alerts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".alert", Long.class);
    }

    @Test
    @Req("REQ-DET-001")
    void alertGoesToThePrimaryCustomerWithEvidenceAndLinkedTransactions() throws IOException {
        fx.liveBatch("B1", "2026-09-30");
        fx.account("A1", "DEPOSIT", "CUST-PRIMARY", "2020-01-01");
        cashDay("T1", "2026-09-30", "70");
        cashDay("T2", "2026-09-30", "80");
        fx.txn("T3", "A1", "WIRE_IN", "CREDIT", "9999", "2026-09-30");    // not cash: not evidence
        loadRule(0);

        DetectionService.Result r = detection.detect(D);

        assertThat(r).isEqualTo(new DetectionService.Result(1, 0, 1));
        var a = jdbc.queryForMap("SELECT alert_id, rule_code, rule_version, customer_id, account_id, product_type, summary, CAST(evidence AS text) AS evidence, handed_off_ts FROM " + aml + ".alert");
        assertThat(a).containsEntry("rule_code", "T_RULE").containsEntry("rule_version", 1).containsEntry("customer_id", "CUST-PRIMARY")
                .containsEntry("account_id", "A1").containsEntry("product_type", "DEPOSIT").containsEntry("summary", "Cash over $100 in a day")
                .containsEntry("handed_off_ts", null);
        assertThat((String) a.get("evidence")).contains("\"txn_count\": 2").contains("\"total\": 150");
        assertThat(jdbc.queryForList("SELECT transaction_id FROM " + aml + ".alert_txn WHERE alert_id = ?", String.class, a.get("alert_id")))
                .containsExactlyInAnyOrder("T1", "T2");
    }

    @Test
    @Req("REQ-DET-002")
    void detectionIsRefusedWithoutALiveBatchForTheDate() throws IOException {
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        loadRule(0);
        fx.liveBatch("B0", "2026-09-29");

        assertThatThrownBy(() -> detection.detect(D)).hasMessageContaining("No live (promoted) batch for business date 2026-09-30");
        assertThat(alerts()).isZero();
    }

    @Test
    @Req("REQ-DET-003")
    void rerunReplacesUnsentAlertsWithoutDuplicates() throws IOException {
        fx.liveBatch("B1", "2026-09-30");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        cashDay("T1", "2026-09-30", "150");
        loadRule(0);

        detection.detect(D);
        detection.detect(D);
        assertThat(alerts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".alert_txn", Long.class)).isEqualTo(1);

        jdbc.update("DELETE FROM " + props.schemas().mst() + ".txn WHERE transaction_id = 'T1'");   // corrected data no longer hits
        detection.detect(D);
        assertThat(alerts()).isZero();
    }

    @Test
    @Req("REQ-DET-004")
    void handedOffAlertsAreNeverDeletedOrDuplicated() throws IOException {
        fx.liveBatch("B1", "2026-09-30");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        cashDay("T1", "2026-09-30", "150");
        loadRule(0);
        detection.detect(D);
        long id = jdbc.queryForObject("SELECT alert_id FROM " + aml + ".alert", Long.class);
        jdbc.update("UPDATE " + aml + ".alert SET handed_off_ts = CURRENT_TIMESTAMP");

        detection.detect(D);
        assertThat(jdbc.queryForList("SELECT alert_id FROM " + aml + ".alert", Long.class)).containsExactly(id);

        jdbc.update("DELETE FROM " + props.schemas().mst() + ".txn WHERE transaction_id = 'T1'");
        detection.detect(D);
        assertThat(jdbc.queryForList("SELECT alert_id FROM " + aml + ".alert", Long.class)).containsExactly(id);
    }

    @Test
    @Req("REQ-DET-005")
    void suppressDaysStopsTheSameAccountAlertingAgainSoon() throws IOException {
        fx.liveBatch("B1", "2026-09-29");
        fx.liveBatch("B2", "2026-09-30");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        cashDay("T1", "2026-09-29", "150");
        cashDay("T2", "2026-09-30", "150");
        loadRule(3);

        detection.detect(LocalDate.parse("2026-09-29"));
        detection.detect(D);

        assertThat(jdbc.queryForList("SELECT CAST(business_date AS text) FROM " + aml + ".alert", String.class)).containsExactly("2026-09-29");

        // outside the suppression period the account alerts again
        jdbc.update("UPDATE " + aml + ".rule SET suppress_days = 0");
        detection.detect(D);
        assertThat(alerts()).isEqualTo(2);
    }

    @Test
    @Req({"REQ-DET-006", "REQ-DET-007"})
    void aFailingRuleIsRecordedAndDoesNotStopTheOthers() throws IOException {
        fx.liveBatch("B1", "2026-09-30");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        cashDay("T1", "2026-09-30", "150");
        loadRule(0);
        jdbc.update("INSERT INTO " + aml + ".rule (rule_code, version, name, template_code, status, config) VALUES"
                + " ('A_BROKEN', 1, 'Broken', 'NO_SUCH_TEMPLATE', 'ACTIVE', CAST('{}' AS jsonb))");

        DetectionService.Result r = detection.detect(D);

        assertThat(r).isEqualTo(new DetectionService.Result(2, 1, 1));
        assertThat(jdbc.queryForList("SELECT rule_code || ':' || status || ':' || alerts_created FROM " + aml + ".rule_run ORDER BY rule_code", String.class))
                .containsExactly("A_BROKEN:FAILED:0", "T_RULE:SUCCESS:1");
        assertThat(jdbc.queryForObject("SELECT error_msg FROM " + aml + ".rule_run WHERE rule_code = 'A_BROKEN'", String.class)).contains("Unknown template");
        assertThat(jdbc.queryForObject("SELECT CAST(business_date AS text) FROM " + aml + ".rule_run WHERE rule_code = 'T_RULE'", String.class)).isEqualTo("2026-09-30");
        assertThat(jdbc.queryForObject("SELECT ended_ts IS NOT NULL FROM " + aml + ".rule_run WHERE rule_code = 'T_RULE'", Boolean.class)).isTrue();
    }
}
