package com.fisre.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.rules.RuleLoader;
import com.fisre.engine.rules.RuleSpec;
import com.fisre.engine.spec.Req;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Proves the scale properties on a few rows: the plans touch only the partitions a rule's window needs. */
@SpringBootTest
class PlanIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired FisreProperties props;
    @Autowired List<Template> templates;

    Set<String> partitionsTouched(RuleSpec spec, LocalDate asOf) {
        Template t = templates.stream().filter(x -> x.code().equals(spec.template())).findFirst().orElseThrow();
        Template.Built b = t.build(spec.config(), asOf, props.schemas().mst());
        String sql = "EXPLAIN " + b.hitsSql();
        StringBuilder plan = new StringBuilder();
        named.queryForList(sql, b.params(), String.class).forEach(plan::append);
        Matcher m = Pattern.compile("txn_(\\d{8})").matcher(plan);
        Set<String> out = new TreeSet<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @Req("REQ-DET-010")
    void aRuleReadsOnlyThePartitionsInsideItsWindow() {
        Fixtures fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        for (int day = 1; day <= 30; day++) {
            String d = String.format("2026-09-%02d", day);
            fx.txn("T" + day, "A1", "CASH_DEPOSIT", "CREDIT", "9000", d);
        }
        jdbc.execute("ANALYZE " + props.schemas().mst() + ".txn");
        RuleSpec structuring = RuleLoader.read(Path.of("..", "specs", "rules", "STRUCTURING_CASH_DEPOSITS.yml"));   // 5-day window
        RuleSpec largeCash = RuleLoader.read(Path.of("..", "specs", "rules", "LARGE_CASH_DAILY.yml"));               // 1-day window

        Set<String> five = partitionsTouched(structuring, LocalDate.parse("2026-09-30"));
        Set<String> one = partitionsTouched(largeCash, LocalDate.parse("2026-09-30"));

        assertThat(five).as("5-day window out of 30 partitions").isSubsetOf("20260926", "20260927", "20260928", "20260929", "20260930").isNotEmpty();
        assertThat(one).as("1-day window").containsExactly("20260930");
    }
}
