package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.rules.RuleLoader;
import com.fisre.engine.rules.RuleSpec;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** ML_SCORE integration (ADR-0009): model lifecycle, fail-safe and shadow mode. */
@SpringBootTest
class MlScoreIT {

    static final LocalDate D = LocalDate.parse("2026-10-01");
    static final String AS_OF = "2026-09-30";

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;

    String aml;
    Fixtures fx;

    @BeforeEach
    void reset() {
        aml = props.schemas().aml();
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B-1", D.toString());
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.txn("T1", "A1", "WIRE_IN", "CREDIT", "9000.00", AS_OF);
    }

    private RuleSpec mlRule(String status) throws IOException {
        RuleSpec s = RuleLoader.readAll(Path.of("..", "specs", "rules")).stream().filter(r -> r.code().equals("ML_ANOMALY")).findFirst().orElseThrow();
        return new RuleSpec(s.code(), s.name(), s.description(), s.template(), status, 0, s.config(), s.sourceFile());
    }

    private long alerts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".alert WHERE rule_code = 'ML_ANOMALY'", Long.class);
    }

    @Test
    @Req("REQ-ML-009")
    void scoresOfARetiredModelNeverRaiseAlerts() throws IOException {
        fx.mlModel("account_anomaly", "old", "RETIRED");
        fx.mlModel("account_anomaly", "new", "ACTIVE");
        fx.mlScore("old", AS_OF, "A1", "DEPOSIT", 0.9999, 1);
        fx.mlScore("new", AS_OF, "A1", "DEPOSIT", 0.50, 1);   // the active model sees nothing unusual
        loader.load(List.of(mlRule("ACTIVE")), "ML_ANOMALY.yml");

        assertThat(detection.detect(D).rulesFailed()).isZero();
        assertThat(alerts()).isZero();
    }

    @Test
    @Req("REQ-ML-010")
    void anActiveMlRuleWithoutScoresFailsInsteadOfFindingNothing() throws IOException {
        fx.mlModel("account_anomaly", "v1", "ACTIVE");        // model exists, but nothing was scored for the day
        loader.load(List.of(mlRule("ACTIVE")), "ML_ANOMALY.yml");

        DetectionService.Result r = detection.detect(D);

        assertThat(r.rulesFailed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status || ': ' || error_msg FROM " + aml + ".rule_run WHERE rule_code = 'ML_ANOMALY'", String.class))
                .startsWith("FAILED").contains("No ML scores");
        // the delivery for that date stays invisible to case management
        assertThat(jdbc.queryForObject("SELECT status FROM " + aml + ".alert_delivery WHERE business_date = ?", String.class, java.sql.Date.valueOf(D))).isEqualTo("OPEN");
    }

    @Test
    @Req("REQ-ML-011")
    void shadowModeRecordsScoresButCreatesNoAlerts() throws IOException {
        fx.mlModel("account_anomaly", "v1", "ACTIVE");
        fx.mlScore("v1", AS_OF, "A1", "DEPOSIT", 0.9999, 1);
        RuleSpec shipped = RuleLoader.readAll(Path.of("..", "specs", "rules")).stream().filter(r -> r.code().equals("ML_ANOMALY")).findFirst().orElseThrow();
        assertThat(shipped.status()).as("the ML rule ships in shadow mode").isEqualTo("DRAFT");
        loader.load(List.of(shipped), "ML_ANOMALY.yml");

        assertThat(detection.detect(D).alertsCreated()).isZero();
        assertThat(alerts()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".v_ml_scores WHERE account_id = 'A1'", Long.class)).isEqualTo(1);
    }

    @Test
    @Req("REQ-ML-008")
    void liveRuleRaisesAnAlertWithModelEvidence() throws IOException {
        fx.mlModel("account_anomaly", "v1", "ACTIVE");
        fx.mlScore("v1", AS_OF, "A1", "DEPOSIT", 0.9999, 1);
        loader.load(List.of(mlRule("ACTIVE")), "ML_ANOMALY.yml");

        detection.detect(D);

        assertThat(jdbc.queryForObject("SELECT evidence->>'model_version' || ' ' || (evidence->>'rank') || ' ' || (evidence->'top_features'->0->>'feature') FROM "
                + aml + ".alert WHERE rule_code = 'ML_ANOMALY'", String.class)).isEqualTo("v1 1 total_1d");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".alert_txn", Long.class)).isEqualTo(1);
    }
}
