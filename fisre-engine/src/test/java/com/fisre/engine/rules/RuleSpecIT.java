package com.fisre.engine.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.detect.DetectionService;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The factory test: every rule spec in specs/rules carries its own scenarios, and each one is run against
 * the database. Adding a rule means adding a YAML file; this test picks it up automatically.
 */
@SpringBootTest
class RuleSpecIT {

    static final Path RULES = Path.of("..", "specs", "rules");

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired DetectionService detection;

    @TestFactory
    @Req({"REQ-RULE-005", "REQ-RULE-006", "REQ-RULE-007", "REQ-RULE-008", "REQ-RULE-009", "REQ-RULE-011"})
    Stream<DynamicTest> everyRuleSpecScenarioPasses() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        Set<String> templatesUsed = new TreeSet<>();
        for (RuleSpec spec : RuleLoader.readAll(RULES)) {
            templatesUsed.add(spec.template());
            JsonNode scenarios = RuleLoader.YAML.readTree(RULES.resolve(spec.sourceFile()).toFile()).path("tests");
            int positives = 0;
            int negatives = 0;
            for (JsonNode sc : scenarios) {
                if (sc.path("expect_alerts").isEmpty()) {
                    negatives++;
                } else {
                    positives++;
                }
                tests.add(dynamicTest(spec.code() + ": " + sc.path("name").asText(), () -> run(spec, sc)));
            }
            int pos = positives;
            int neg = negatives;
            tests.add(dynamicTest(spec.code() + ": has a positive and a negative scenario",
                    () -> {
                        assertThat(pos).as("scenarios expecting an alert").isGreaterThanOrEqualTo(1);
                        assertThat(neg).as("scenarios expecting no alert").isGreaterThanOrEqualTo(1);
                    }));
        }
        tests.add(dynamicTest("every template is used by at least one rule spec",
                () -> assertThat(templatesUsed).containsExactlyInAnyOrderElementsOf(loader.templates().keySet())));
        return tests.stream();
    }

    private void run(RuleSpec spec, JsonNode sc) {
        Fixtures fx = new Fixtures(jdbc, props);
        fx.resetAll();
        String asOf = sc.get("as_of").asText();
        LocalDate businessDate = LocalDate.parse(asOf).plusDays(props.tuning().postingOffsetDays());
        fx.liveBatch("B-" + businessDate, businessDate.toString());
        for (JsonNode a : sc.path("accounts")) {
            String id = a.get("id").asText();
            fx.account(id, a.get("product").asText(), a.path("customer").asText("C-" + id), a.path("open_date").asText("2020-01-01"));
        }
        for (JsonNode t : sc.path("txns")) {
            fx.txn(t.get("id").asText(), t.get("account").asText(), t.get("type").asText(), t.get("direction").asText(),
                    t.get("amount").decimalValue().setScale(2), t.get("date").asText(), t.path("time").asText("12:00"));
        }
        loader.load(List.of(spec), spec.sourceFile());

        DetectionService.Result r = detection.detect(businessDate);

        assertThat(r.rulesFailed()).isZero();
        List<String> alerted = jdbc.queryForList("SELECT account_id FROM " + props.schemas().aml() + ".alert WHERE rule_code = ?", String.class, spec.code());
        List<String> expected = new ArrayList<>();
        sc.path("expect_alerts").forEach(n -> expected.add(n.asText()));
        assertThat(alerted).containsExactlyInAnyOrderElementsOf(expected);
    }
}
