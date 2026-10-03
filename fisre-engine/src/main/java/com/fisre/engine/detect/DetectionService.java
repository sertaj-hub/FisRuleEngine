package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.db.Dialect;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evaluates every ACTIVE rule for one business date with set-based SQL (specs/requirements/detection.md).
 * Each rule runs in its own transaction; a failing rule is recorded and does not stop the others.
 */
@Service
public class DetectionService {

    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Result(int rulesRun, int rulesFailed, long alertsCreated) {}

    private record ActiveRule(long id, String code, int version, String name, String template, int suppressDays, JsonNode config) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Dialect dialect;
    private final Map<String, Template> templates;
    private final String mst;
    private final String aml;

    public DetectionService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Dialect dialect,
                            List<Template> templates, FisreProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.dialect = dialect;
        this.templates = templates.stream().collect(Collectors.toMap(Template::code, Function.identity()));
        this.mst = props.schemas().mst();
        this.aml = props.schemas().aml();
    }

    public Result detect(LocalDate date) {
        Long live = jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".load_batch WHERE business_date = :d AND status IN ('PROMOTED', 'CLEANED')",
                Map.of("d", java.sql.Date.valueOf(date)), Long.class);
        if (live == null || live == 0) {
            throw new IllegalStateException("No live (promoted) batch for business date " + date + "; promote a batch first");
        }
        int run = 0;
        int failed = 0;
        long alerts = 0;
        for (ActiveRule r : activeRules()) {
            long runId = startRun(r, date);
            try {
                Long created = tx.execute(s -> detectOne(r, date, runId));
                finishRun(runId, "SUCCESS", created, null);
                alerts += created;
                run++;
                log.info("Rule {} for {}: {} alert(s)", r.code(), date, created);
            } catch (RuntimeException e) {
                finishRun(runId, "FAILED", 0L, e.toString());
                failed++;
                run++;
                log.error("Rule {} failed for {}", r.code(), date, e);
            }
        }
        return new Result(run, failed, alerts);
    }

    private long detectOne(ActiveRule r, LocalDate date, long runId) {
        Template t = templates.get(r.template());
        if (t == null) {
            throw new IllegalStateException("Unknown template '" + r.template() + "'");
        }
        t.validate(r.config());
        Template.Built b = t.build(r.config(), date, mst);
        Map<String, Object> params = new HashMap<>(b.params());
        params.put("rule_id", r.id());
        params.put("rule_code", r.code());
        params.put("rule_version", r.version());
        params.put("summary", r.name());
        params.put("d", java.sql.Date.valueOf(date));
        params.put("run_id", runId);
        params.put("suppress_from", java.sql.Date.valueOf(date.minusDays(r.suppressDays())));

        // Re-run: drop this rule's alerts for the date unless they were already handed off (alert_txn cascades).
        jdbc.update("DELETE FROM " + aml + ".alert WHERE rule_code = :rule_code AND business_date = :d AND handed_off_ts IS NULL", params);

        String hits = "(" + b.hitsSql() + ") h";
        String suppression = r.suppressDays() > 0
                ? " WHERE NOT EXISTS (SELECT 1 FROM " + aml + ".alert p WHERE p.rule_code = :rule_code AND p.account_id = h.account_id"
                + " AND p.business_date >= :suppress_from AND p.business_date < :d)"
                : " WHERE 1 = 1";
        int created = jdbc.update("INSERT INTO " + aml + ".alert (rule_id, rule_code, rule_version, business_date, account_id, customer_id,"
                + " product_type, summary, evidence, run_id) SELECT :rule_id, :rule_code, :rule_version, :d, h.account_id, h.customer_id,"
                + " h.product_type, :summary, h.evidence, :run_id FROM " + hits + suppression
                + " ON CONFLICT (rule_code, account_id, business_date) DO NOTHING", params);

        jdbc.update("INSERT INTO " + aml + ".alert_txn (alert_id, transaction_id) SELECT al.alert_id, e.transaction_id FROM ("
                + b.evidenceSql().replace("{hits}", hits) + ") e JOIN " + aml + ".alert al ON al.rule_code = :rule_code"
                + " AND al.business_date = :d AND al.account_id = e.account_id ON CONFLICT DO NOTHING", params);
        return created;
    }

    private List<ActiveRule> activeRules() {
        return jdbc.query("SELECT rule_id, rule_code, version, name, template_code, suppress_days, CAST(config AS text) AS config FROM "
                + aml + ".rule WHERE status = 'ACTIVE' ORDER BY rule_code", Map.of(), (rs, i) -> {
            try {
                return new ActiveRule(rs.getLong("rule_id"), rs.getString("rule_code"), rs.getInt("version"), rs.getString("name"),
                        rs.getString("template_code"), rs.getInt("suppress_days"), JSON.readTree(rs.getString("config")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private long startRun(ActiveRule r, LocalDate date) {
        return jdbc.queryForObject("INSERT INTO " + aml + ".rule_run (rule_id, rule_code, business_date, status, started_ts) VALUES"
                + " (:id, :c, :d, 'RUNNING', " + dialect.now() + ") RETURNING run_id",
                new MapSqlParameterSource().addValue("id", r.id()).addValue("c", r.code()).addValue("d", java.sql.Date.valueOf(date)), Long.class);
    }

    private void finishRun(long runId, String status, Long created, String error) {
        String msg = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        jdbc.update("UPDATE " + aml + ".rule_run SET status = :s, ended_ts = " + dialect.now() + ", alerts_created = :n, error_msg = :m WHERE run_id = :id",
                new MapSqlParameterSource().addValue("s", status).addValue("n", created).addValue("m", msg).addValue("id", runId));
    }
}
