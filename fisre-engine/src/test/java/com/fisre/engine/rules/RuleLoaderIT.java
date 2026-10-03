package com.fisre.engine.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.detect.DetectionService;
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
class RuleLoaderIT {

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

    static String yaml(String code, String status, String minSum) {
        return "code: " + code + "\nname: Test rule " + code + "\ntemplate: AGGREGATE\nstatus: " + status + "\nsuppress_days: 0\n"
                + "config:\n  window_days: 1\n  filter: { cash: true }\n  min_sum: " + minSum + "\n";
    }

    Path write(String name, String content) throws IOException {
        return Files.writeString(tmp.resolve(name), content);
    }

    long rules() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".rule", Long.class);
    }

    @Test
    @Req("REQ-RULE-001")
    void everySpecInTheRulesDirectoryIsStoredAsAVersionedRow() throws IOException {
        long specs = Files.list(Path.of("..", "specs", "rules")).filter(p -> p.toString().endsWith(".yml")).count();

        RuleLoader.LoadResult r = loader.load(Path.of("..", "specs", "rules"));

        assertThat(r.created()).isEqualTo(specs);
        assertThat(rules()).isEqualTo(specs);
        assertThat(jdbc.queryForObject("SELECT template_code FROM " + aml + ".rule WHERE rule_code = 'STRUCTURING_CASH_DEPOSITS'", String.class)).isEqualTo("AGGREGATE");
        assertThat(jdbc.queryForObject("SELECT suppress_days FROM " + aml + ".rule WHERE rule_code = 'STRUCTURING_CASH_DEPOSITS'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT CAST(config AS text) FROM " + aml + ".rule WHERE rule_code = 'STRUCTURING_CASH_DEPOSITS'", String.class)).contains("min_count");
    }

    @Test
    @Req("REQ-RULE-002")
    void unchangedSpecMakesNoNewVersion_changedSpecRetiresTheOldOne() throws IOException {
        write("R1.yml", yaml("R1", "ACTIVE", "100"));
        loader.load(tmp);
        RuleLoader.LoadResult again = loader.load(tmp);
        assertThat(again.created()).isZero();
        assertThat(rules()).isEqualTo(1);

        write("R1.yml", yaml("R1", "ACTIVE", "250"));
        RuleLoader.LoadResult changed = loader.load(tmp);

        assertThat(changed.created()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT version || ':' || status FROM " + aml + ".rule WHERE rule_code = 'R1' ORDER BY version", String.class))
                .containsExactly("1:RETIRED", "2:ACTIVE");
    }

    @Test
    @Req("REQ-RULE-003")
    void invalidSpecsAreRejectedWithTheRuleNameAndNothingIsStored() throws IOException {
        write("GOOD.yml", yaml("GOOD", "ACTIVE", "100"));
        String[][] bad = {
                {"unknown template", "code: BAD\nname: x\ntemplate: NOPE\nstatus: ACTIVE\nconfig: {}\n", "unknown template"},
                {"missing param", "code: BAD\nname: x\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig: { min_sum: 5 }\n", "window_days"},
                {"unknown key", "code: BAD\nname: x\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig: { window_days: 1, min_sum: 5, severity: HIGH }\n", "unknown config key 'severity'"},
                {"bad direction", "code: BAD\nname: x\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig: { window_days: 1, min_sum: 5, filter: { direction: SIDEWAYS } }\n", "direction"},
                {"no threshold", "code: BAD\nname: x\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig: { window_days: 1 }\n", "min_count"},
                {"bad product", "code: BAD\nname: x\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig: { window_days: 1, min_sum: 5, product_types: [BOAT] }\n", "unknown product"},
                {"bad status", "code: BAD\nname: x\ntemplate: AGGREGATE\nstatus: LIVE\nconfig: { window_days: 1, min_sum: 5 }\n", "status"},
        };
        for (String[] b : bad) {
            write("BAD.yml", b[1]);
            assertThatThrownBy(() -> loader.load(tmp)).as(b[0]).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rule BAD").hasMessageContaining(b[2]);
            assertThat(rules()).as(b[0] + ": nothing stored, not even the good rule").isZero();
        }
    }

    @Test
    @Req("REQ-RULE-004")
    void onlyActiveRulesRun() throws IOException {
        write("A.yml", yaml("ACTIVE_ONE", "ACTIVE", "100"));
        write("D.yml", yaml("DRAFT_ONE", "DRAFT", "100"));
        write("R.yml", yaml("RETIRED_ONE", "RETIRED", "100"));
        loader.load(tmp);
        fx.liveBatch("B1", "2026-09-30");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "500", "2026-09-30");

        DetectionService.Result r = detection.detect(LocalDate.parse("2026-09-30"));

        assertThat(r.rulesRun()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT rule_code FROM " + aml + ".alert", String.class)).containsExactly("ACTIVE_ONE");
        assertThat(jdbc.queryForList("SELECT rule_code FROM " + aml + ".rule_run", String.class)).containsExactly("ACTIVE_ONE");
    }
}
