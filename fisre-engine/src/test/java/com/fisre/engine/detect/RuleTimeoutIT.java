package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.rules.RuleLoader;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** A rule that runs too long is cancelled by its own time limit, recorded FAILED, and does not stop the others. */
@SpringBootTest(properties = "fisre.tuning.rule-timeout-seconds=1")
@Import(RuleTimeoutIT.SleepyConfig.class)
class RuleTimeoutIT {

    /** Test-only template whose query sleeps for 8 seconds. */
    @TestConfiguration
    static class SleepyConfig {
        @Bean
        Template sleepyTemplate() {
            return new Template() {
                @Override public String code() { return "SLEEPY"; }
                @Override public void validate(JsonNode config) { }
                @Override public Built build(JsonNode config, LocalDate d, String mst) {
                    return new Built("SELECT 'A1' AS account_id, 'C1' AS customer_id, 'DEPOSIT' AS product_type, jsonb_build_object() AS evidence FROM pg_sleep(8)",
                            "SELECT 'A1' AS account_id, 'T1' AS transaction_id, DATE '2026-09-30' AS posting_date FROM {hits}", Map.of());
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @TempDir Path tmp;

    @Test
    @Req("REQ-OPS-002")
    void aTimedOutRuleIsRecordedFailedAndTheOthersStillRun() throws IOException {
        Fixtures fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "150", "2026-09-30");
        Files.writeString(tmp.resolve("OK.yml"), "code: OK_RULE\nname: Cash\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);
        jdbc.update("INSERT INTO aml.rule (rule_code, version, name, template_code, status, config) VALUES ('SLOW_RULE', 1, 'Slow', 'SLEEPY', 'ACTIVE', CAST('{}' AS jsonb))");

        long start = System.nanoTime();
        DetectionService.Result r = detection.detect(LocalDate.parse("2026-10-01"));
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(r.rulesFailed()).isEqualTo(1);
        assertThat(seconds).as("cancelled by the 1 s limit, not run for 8 s").isLessThan(6);
        assertThat(jdbc.queryForObject("SELECT status FROM aml.rule_run WHERE rule_code = 'SLOW_RULE'", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_msg FROM aml.rule_run WHERE rule_code = 'SLOW_RULE'", String.class)).contains("statement timeout");
        assertThat(jdbc.queryForObject("SELECT status FROM aml.rule_run WHERE rule_code = 'OK_RULE'", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert WHERE rule_code = 'OK_RULE'", Long.class)).isEqualTo(1);
    }
}
