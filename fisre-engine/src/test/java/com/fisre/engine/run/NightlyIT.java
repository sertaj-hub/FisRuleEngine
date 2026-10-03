package com.fisre.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.rules.RuleLoader;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NightlyIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired NightlyService nightly;
    @TempDir Path tmp;

    @BeforeEach
    void rule() throws IOException {
        new Fixtures(jdbc, props).resetAll();
        Files.writeString(tmp.resolve("T_RULE.yml"), "code: T_RULE\nname: Cash over $100 in a day\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n"
                + "  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
    }

    /** A LOADED batch for 2026-10-01 (posting day 2026-09-30) with one customer, one account and two cash deposits. */
    void loadedBatch(String id, String customerType) {
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status) VALUES (?, DATE '2026-10-01', 'LOADED')", id);
        jdbc.update("INSERT INTO stg.customer (batch_id, customer_id, customer_type, full_name) VALUES (?, 'C1', ?, 'Ada')", id, customerType);
        jdbc.update("INSERT INTO stg.account (batch_id, account_id, primary_customer_id, product_type, status, open_date) VALUES (?, 'A1', 'C1', 'DEPOSIT', 'ACTIVE', DATE '2020-01-01')", id);
        for (String[] t : new String[][] {{"T1", "60"}, {"T2", "70"}}) {
            jdbc.update("INSERT INTO stg.txn (batch_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                    + " VALUES (?, ?, 'A1', ?, ?, 'CASH_DEPOSIT', 'CREDIT', ?, 'USD')", id, t[0], Timestamp.valueOf("2026-09-30 09:00:00"),
                    Date.valueOf("2026-09-30"), new BigDecimal(t[1]));
        }
    }

    List<String> recorded() {
        return jdbc.queryForList("SELECT step || ':' || status FROM aml.nightly_run ORDER BY run_id", String.class);
    }

    @Test
    @Req("REQ-RUN-003")
    void promoteDetectRetainRunInOrder_andARetrySkipsPromote() {
        loadedBatch("N1", "INDIVIDUAL");

        List<NightlyService.Step> steps = nightly.run("N1", D);

        assertThat(steps).extracting(NightlyService.Step::step).containsExactly("PROMOTE", "DETECT", "RETAIN");
        assertThat(steps).extracting(NightlyService.Step::status).containsExactly("SUCCESS", "SUCCESS", "SUCCESS");
        assertThat(recorded()).containsExactly("PROMOTE:SUCCESS", "DETECT:SUCCESS", "RETAIN:SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM aml.load_batch WHERE batch_id = 'N1'", String.class)).isEqualTo("CLEANED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.nightly_run WHERE ended_ts IS NOT NULL", Long.class)).isEqualTo(3);

        List<NightlyService.Step> retry = nightly.run("N1", D);   // e.g. the scheduler runs it again after a later failure
        assertThat(retry).extracting(NightlyService.Step::status).containsExactly("SKIPPED", "SUCCESS", "SUCCESS");
        assertThat(retry.get(0).message()).contains("already promoted");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert", Long.class)).as("re-detection does not duplicate").isEqualTo(1);
    }

    @Test
    @Req("REQ-RUN-003")
    void aFailingStepStopsTheRun_andLaterStepsAreRecordedSkipped() {
        loadedBatch("N2", "ALIEN");   // fails validation

        List<NightlyService.Step> steps = nightly.run("N2", D);

        assertThat(steps).extracting(NightlyService.Step::status).containsExactly("FAILED", "SKIPPED", "SKIPPED");
        assertThat(steps.get(0).message()).contains("failed validation");
        assertThat(recorded()).containsExactly("PROMOTE:FAILED", "DETECT:SKIPPED", "RETAIN:SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert", Long.class)).isZero();
    }

    @Test
    @Req("REQ-RUN-003")
    void theBatchBusinessDateMustMatch() {
        loadedBatch("N3", "INDIVIDUAL");

        assertThatThrownBy(() -> nightly.run("N3", LocalDate.parse("2026-10-02"))).hasMessageContaining("is for business date 2026-10-01, not 2026-10-02");
        assertThat(recorded()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM aml.load_batch WHERE batch_id = 'N3'", String.class)).isEqualTo("LOADED");
    }
}
