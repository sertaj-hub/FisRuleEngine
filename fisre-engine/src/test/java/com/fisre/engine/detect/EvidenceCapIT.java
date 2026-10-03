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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "fisre.tuning.max-evidence-txns=2")
class EvidenceCapIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;
    @TempDir Path tmp;

    @Test
    @Req("REQ-DET-011")
    void linkedTransactionsAreCappedButEvidenceKeepsTheTrueTotals() throws IOException {
        Fixtures fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        for (int i = 1; i <= 5; i++) {
            fx.txn("T" + i, "A1", "CASH_DEPOSIT", "CREDIT", "100", "2026-09-30");
        }
        Files.writeString(tmp.resolve("R.yml"), "code: R\nname: Cash\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: 100\n");
        loader.load(tmp);

        detection.detect(LocalDate.parse("2026-10-01"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aml.alert_txn", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT CAST(evidence AS text) FROM aml.alert", String.class)).contains("\"txn_count\": 5").contains("\"total\": 500");
    }
}
