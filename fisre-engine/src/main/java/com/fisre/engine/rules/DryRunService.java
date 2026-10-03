package com.fisre.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.detect.Template;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shows what a proposed rule configuration would have done over the most recent posting days, next to the active version
 * (ADR-0010, REQ-RUI-007). Runs inside a transaction that is always rolled back: no alerts, no rows.
 */
@Service
public class DryRunService {

    public static final int MAX_DAYS = 31;
    private static final long TIMEOUT_SECONDS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Day(LocalDate postingDay, long proposed, Long active, Long added, Long removed, String skipped) {}

    public record Result(String code, String template, Integer activeVersion, int days, List<Day> perDay, long totalProposed, long totalActive,
                         List<Map<String, Object>> sample, String note) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RuleAdminService admin;
    private final RuleLoader loader;
    private final Map<String, Template> templates;
    private final String mst;
    private final String aml;
    private final boolean allowNestedLoops;

    public DryRunService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, RuleAdminService admin, RuleLoader loader, List<Template> templates, FisreProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.admin = admin;
        this.loader = loader;
        this.templates = templates.stream().collect(Collectors.toMap(Template::code, Function.identity()));
        this.mst = props.schemas().mst();
        this.aml = props.schemas().aml();
        this.allowNestedLoops = props.tuning().allowNestedLoops();
    }

    /** Dry-run of a stored version; recorded in the audit trail, which approval requires. */
    public Result runVersion(String actor, String code, int version, int days) {
        RuleAdminService.RuleVersion v = admin.version(code, version);
        Result r = run(code, v.template(), v.config(), days);
        admin.log(code, version, "DRYRUN", actor, summary(r));
        return r;
    }

    /** Dry-run of an unsaved configuration from the editor. Audited without a version. */
    public Result runAdHoc(String actor, String code, String template, JsonNode config, int days) {
        loader.validate(new RuleSpec(code == null || code.isBlank() ? "ADHOC" : code, "dry run", null, template, "DRAFT", 0, config == null ? JSON.createObjectNode() : config, "UI"));
        Result r = run(code, template, config, days);
        admin.log(code == null || code.isBlank() ? "ADHOC" : code, null, "DRYRUN_ADHOC", actor, summary(r));
        return r;
    }

    private Result run(String code, String templateCode, JsonNode config, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
        Template t = templates.get(templateCode);
        if (t == null) {
            throw new IllegalArgumentException("unknown template '" + templateCode + "'");
        }
        t.validate(config);
        Map<String, Object> active = code == null ? null : activeOf(code);
        Template activeTemplate = active == null ? null : templates.get((String) active.get("template_code"));
        JsonNode activeConfig = active == null ? null : readJson((String) active.get("config"));

        LocalDate last = jdbc.queryForObject("SELECT MAX(posting_date) FROM " + mst + ".txn", Map.of(), LocalDate.class);
        if (last == null) {
            throw new WorkflowException("There are no transactions in " + mst + " to run against");
        }
        return tx.execute(s -> {
            s.setRollbackOnly();                                             // nothing from a dry-run is ever kept
            jdbc.getJdbcOperations().execute("SET LOCAL statement_timeout = " + (TIMEOUT_SECONDS * 1000));
            if (!allowNestedLoops) {
                jdbc.getJdbcOperations().execute("SET LOCAL enable_nestloop = off");
            }
            String cols = "(account_id VARCHAR(40), customer_id VARCHAR(40), product_type VARCHAR(20), evidence JSONB) ON COMMIT DROP";
            jdbc.getJdbcOperations().execute("CREATE TEMP TABLE dr_new " + cols);
            jdbc.getJdbcOperations().execute("CREATE TEMP TABLE dr_cur " + cols);
            List<Day> perDay = new ArrayList<>();
            List<Map<String, Object>> sample = new ArrayList<>();
            long totalNew = 0;
            long totalCur = 0;
            for (int i = 0; i < days; i++) {
                LocalDate d = last.minusDays(i);
                jdbc.getJdbcOperations().execute("TRUNCATE dr_new, dr_cur");
                try {
                    t.requireInputs(config, d, jdbc, aml);
                } catch (IllegalStateException e) {
                    perDay.add(new Day(d, 0, null, null, null, e.getMessage()));
                    continue;
                }
                Template.Built b = t.build(config, d, mst, aml);
                jdbc.update("INSERT INTO dr_new SELECT h.account_id, h.customer_id, h.product_type, h.evidence FROM (" + b.hitsSql() + ") h", new HashMap<>(b.params()));
                long proposed = count("dr_new");
                Long cur = null;
                Long added = null;
                Long removed = null;
                if (activeTemplate != null) {
                    boolean usable = true;
                    try {
                        activeTemplate.requireInputs(activeConfig, d, jdbc, aml);
                    } catch (IllegalStateException e) {
                        usable = false;
                    }
                    if (usable) {
                        Template.Built c = activeTemplate.build(activeConfig, d, mst, aml);
                        jdbc.update("INSERT INTO dr_cur SELECT h.account_id, h.customer_id, h.product_type, h.evidence FROM (" + c.hitsSql() + ") h", new HashMap<>(c.params()));
                        cur = count("dr_cur");
                        added = jdbc.queryForObject("SELECT COUNT(*) FROM dr_new n WHERE NOT EXISTS (SELECT 1 FROM dr_cur c WHERE c.account_id = n.account_id)", Map.of(), Long.class);
                        removed = jdbc.queryForObject("SELECT COUNT(*) FROM dr_cur c WHERE NOT EXISTS (SELECT 1 FROM dr_new n WHERE n.account_id = c.account_id)", Map.of(), Long.class);
                        totalCur += cur;
                    }
                }
                totalNew += proposed;
                if (sample.size() < 10 && proposed > 0) {
                    for (Map<String, Object> row : jdbc.queryForList("SELECT account_id, customer_id, product_type, CAST(evidence AS text) AS evidence FROM dr_new ORDER BY account_id LIMIT "
                            + (10 - sample.size()), Map.of())) {
                        Map<String, Object> m = new HashMap<>(row);
                        m.put("posting_day", d.toString());
                        m.put("evidence", readJson((String) row.get("evidence")));
                        sample.add(m);
                    }
                }
                perDay.add(new Day(d, proposed, cur, added, removed, null));
            }
            return new Result(code, templateCode, active == null ? null : ((Number) active.get("version")).intValue(), days, perDay, totalNew, totalCur, sample,
                    "Ignores suppress_days. Nothing was saved. Posting days are counted back from the latest day in " + mst + ".txn (" + last + ").");
        });
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    private Map<String, Object> activeOf(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version, template_code, CAST(config AS text) AS config FROM " + aml + ".rule WHERE rule_code = :c AND status = 'ACTIVE'", Map.of("c", code));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static JsonNode readJson(String s) {
        try {
            return JSON.readTree(s);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode summary(Result r) {
        ObjectNode n = JSON.createObjectNode();
        n.put("days", r.days());
        n.put("proposed_total", r.totalProposed());
        n.put("active_total", r.totalActive());
        n.put("skipped_days", r.perDay().stream().filter(d -> d.skipped() != null).count());
        if (r.activeVersion() != null) {
            n.put("compared_with_version", r.activeVersion());
        }
        return n;
    }
}
