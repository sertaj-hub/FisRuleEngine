package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

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

/** The case management contract: aml.v_alert_export and aml.ack_alerts (data-contract/alert-export.md). */
@SpringBootTest
class HandoffIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @TempDir Path tmp;

    @BeforeEach
    void alertForA1() throws IOException {
        Fixtures fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        jdbc.update("UPDATE mst.customer SET country_code = 'US', state_code = 'NY' WHERE customer_id = 'C1'");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "70", "2026-09-30");
        fx.txn("T2", "A1", "CASH_DEPOSIT", "CREDIT", "80", "2026-09-30");
        fx.txn("T3", "A1", "WIRE_IN", "CREDIT", "9999", "2026-09-30");   // not part of the evidence
        Files.writeString(tmp.resolve("T_RULE.yml"), "code: T_RULE\nname: Cash over $100 in a day\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n"
                + "  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
        detection.detect(D);
    }

    String payload(String jsonPath) {
        return jdbc.queryForObject("SELECT " + jsonPath + " FROM aml.v_alert_export", String.class);
    }

    @Test
    @Req("REQ-HND-001")
    void viewShowsUnsentAlertsWithASelfContainedPayload() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_export", Long.class)).isEqualTo(1);
        assertThat(payload("payload->'delivery'->>'revision'")).isEqualTo("1");
        assertThat(payload("payload->'delivery'->>'id'")).isEqualTo(payload("delivery_id::text"));
        assertThat(payload("payload->'rule'->>'code'")).isEqualTo("T_RULE");
        assertThat(payload("payload->'rule'->>'name'")).isEqualTo("Cash over $100 in a day");
        assertThat(payload("payload->'rule'->>'version'")).isEqualTo("1");
        assertThat(payload("payload->>'business_date'")).isEqualTo("2026-10-01");
        assertThat(payload("payload->'account'->>'id'")).isEqualTo("A1");
        assertThat(payload("payload->'account'->>'product_type'")).isEqualTo("DEPOSIT");
        assertThat(payload("payload->'customer'->>'id'")).isEqualTo("C1");
        assertThat(payload("payload->'customer'->>'name'")).isEqualTo("Customer C1");
        assertThat(payload("payload->'customer'->>'country'")).isEqualTo("US");
        assertThat(payload("payload->'customer'->>'state'")).isEqualTo("NY");
        assertThat(payload("payload->'evidence'->>'total'")).startsWith("150");
        assertThat(payload("jsonb_array_length(payload->'transactions')::text")).isEqualTo("2");
        assertThat(payload("payload->'transactions'->0->>'transaction_id'")).isEqualTo("T1");
        assertThat(payload("payload->'transactions'->0->>'txn_type'")).isEqualTo("CASH_DEPOSIT");
        assertThat(payload("payload->'transactions'->0->>'posting_date'")).isEqualTo("2026-09-30");
        assertThat(payload("payload->'transactions'->1->>'amount'")).isEqualTo("80.0000");
        assertThat(payload("payload->>'alert_id'")).isEqualTo(payload("alert_id::text"));
    }

    @Test
    @Req("REQ-HND-002")
    void ackMarksAlertsOnce_andAcknowledgedAlertsLeaveTheViewAndSurviveRerun() {
        long id = jdbc.queryForObject("SELECT alert_id FROM aml.v_alert_export", Long.class);

        assertThat(jdbc.queryForObject("SELECT aml.ack_alerts(ARRAY[?, 999999]::bigint[])", Integer.class, id)).as("unknown id ignored").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT aml.ack_alerts(ARRAY[?]::bigint[])", Integer.class, id)).as("idempotent").isEqualTo(0);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_export", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT handed_off_ts IS NOT NULL FROM aml.alert WHERE alert_id = ?", Boolean.class, id)).isTrue();
        detection.detect(D);   // a re-run must not bring it back or duplicate it
        assertThat(jdbc.queryForList("SELECT alert_id FROM aml.alert", Long.class)).containsExactly(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.v_alert_export", Long.class)).isZero();
    }

    @Test
    @Req("REQ-HND-003")
    void theViewColumnsAreAStableContract() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'aml' AND table_name = 'v_alert_export'"
                + " ORDER BY ordinal_position", String.class))
                .containsExactly("alert_id", "delivery_id", "rule_code", "business_date", "account_id", "customer_id", "product_type", "created_ts", "payload");
    }

    @Test
    @Req("REQ-HND-004")
    void theCustomerSnapshotDoesNotChangeWhenMasterDoes() {
        jdbc.update("UPDATE mst.customer SET full_name = 'Renamed Later', country_code = 'CA' WHERE customer_id = 'C1'");

        assertThat(payload("payload->'customer'->>'name'")).isEqualTo("Customer C1");
        assertThat(payload("payload->'customer'->>'country'")).isEqualTo("US");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'aml' AND table_name = 'alert' AND column_name = 'customer_snapshot'", String.class))
                .hasSize(1);
    }
}
