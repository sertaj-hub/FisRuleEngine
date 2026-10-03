package com.fisre.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fisre.engine.config.FisreProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rule workflow behind the configuration UI (ADR-0010, specs/requirements/rule-ui.md): drafts, submission,
 * four-eyes approval, rejection, retirement and the audit trail. Every rule change is a new version.
 */
@Service
public class RuleAdminService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,59}$");

    public record DraftRequest(String code, String name, String description, String template, Integer suppressDays, JsonNode config, String reason) {}

    public record RuleVersion(String ruleCode, int version, String name, String description, String template, String status, int suppressDays,
                              JsonNode config, String source, String authoredBy, String changeReason, String submittedBy, LocalDateTime submittedTs,
                              String decidedBy, LocalDateTime decidedTs, String decisionNote, LocalDateTime createdTs) {}

    public record RuleSummary(String code, String name, String template, Integer activeVersion, int latestVersion, String latestStatus) {}

    public record AuditEntry(long auditId, String ruleCode, Integer version, String action, String actor, LocalDateTime auditTs, JsonNode detail) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RuleLoader loader;
    private final String rule;
    private final String audit;

    public RuleAdminService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, RuleLoader loader, FisreProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.loader = loader;
        this.rule = props.schemas().aml() + ".rule";
        this.audit = props.schemas().aml() + ".rule_audit";
    }

    private static final String COLUMNS = "rule_code, version, name, description, template_code, status, suppress_days, CAST(config AS text) AS config, source,"
            + " authored_by, change_reason, submitted_by, submitted_ts, decided_by, decided_ts, decision_note, created_ts";

    private static RuleVersion map(ResultSet rs, int i) throws SQLException {
        try {
            return new RuleVersion(rs.getString("rule_code"), rs.getInt("version"), rs.getString("name"), rs.getString("description"),
                    rs.getString("template_code"), rs.getString("status"), rs.getInt("suppress_days"), JSON.readTree(rs.getString("config")),
                    rs.getString("source"), rs.getString("authored_by"), rs.getString("change_reason"), rs.getString("submitted_by"), ts(rs, "submitted_ts"),
                    rs.getString("decided_by"), ts(rs, "decided_ts"), rs.getString("decision_note"), ts(rs, "created_ts"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    // ---- reading ----

    public List<RuleSummary> list() {
        Map<String, RuleSummary> out = new LinkedHashMap<>();
        for (RuleVersion v : jdbc.query("SELECT " + COLUMNS + " FROM " + rule + " ORDER BY rule_code, version", Map.of(), RuleAdminService::map)) {
            RuleSummary prev = out.get(v.ruleCode());
            Integer active = "ACTIVE".equals(v.status()) ? Integer.valueOf(v.version()) : prev == null ? null : prev.activeVersion();
            out.put(v.ruleCode(), new RuleSummary(v.ruleCode(), v.name(), v.template(), active, v.version(), v.status()));
        }
        return new ArrayList<>(out.values());
    }

    public List<RuleVersion> versions(String code) {
        List<RuleVersion> v = jdbc.query("SELECT " + COLUMNS + " FROM " + rule + " WHERE rule_code = :c ORDER BY version DESC", Map.of("c", code), RuleAdminService::map);
        if (v.isEmpty()) {
            throw new NotFoundException("No rule '" + code + "'");
        }
        return v;
    }

    public RuleVersion version(String code, int version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + rule + " WHERE rule_code = :c AND version = :v", Map.of("c", code, "v", version), RuleAdminService::map)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("No version " + version + " of rule '" + code + "'"));
    }

    public List<RuleVersion> pending() {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + rule + " WHERE status = 'PENDING_APPROVAL' ORDER BY submitted_ts", Map.of(), RuleAdminService::map);
    }

    public List<AuditEntry> auditTrail(String code) {
        return jdbc.query("SELECT audit_id, rule_code, version, action, actor, audit_ts, CAST(detail AS text) AS detail FROM " + audit
                + " WHERE rule_code = :c ORDER BY audit_id DESC LIMIT 500", Map.of("c", code), (rs, i) -> {
            try {
                return new AuditEntry(rs.getLong("audit_id"), rs.getString("rule_code"), (Integer) rs.getObject("version"), rs.getString("action"),
                        rs.getString("actor"), ts(rs, "audit_ts"), JSON.readTree(rs.getString("detail")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---- drafting ----

    public RuleVersion createDraft(String actor, DraftRequest r) {
        if (r.code() == null || !CODE.matcher(r.code()).matches()) {
            throw new IllegalArgumentException("code must be 3 to 60 characters: capital letters, digits and underscores, starting with a letter");
        }
        String reason = required(r.reason(), "reason", 1000);
        RuleSpec spec = checked(r.code(), r.name(), r.description(), r.template(), r.suppressDays(), r.config());
        return tx.execute(s -> {
            List<RuleVersion> existing = jdbc.query("SELECT " + COLUMNS + " FROM " + rule + " WHERE rule_code = :c ORDER BY version DESC FOR UPDATE",
                    Map.of("c", r.code()), RuleAdminService::map);
            if (!existing.isEmpty() && !existing.get(0).template().equals(spec.template())) {
                throw new IllegalArgumentException("rule " + r.code() + " uses template " + existing.get(0).template() + "; the template of an existing rule cannot change");
            }
            for (RuleVersion e : existing) {
                if (e.status().equals("DRAFT") || e.status().equals("PENDING_APPROVAL")) {
                    throw new WorkflowException("Version " + e.version() + " of " + r.code() + " is still " + e.status() + "; finish or reject it before starting another");
                }
            }
            int version = existing.isEmpty() ? 1 : existing.get(0).version() + 1;
            jdbc.update("INSERT INTO " + rule + " (rule_code, version, name, description, template_code, status, suppress_days, config, source, authored_by, change_reason)"
                    + " VALUES (:c, :v, :n, :d, :t, 'DRAFT', :sd, CAST(:cfg AS jsonb), 'UI', :a, :reason)", params(spec, version).addValue("a", actor).addValue("reason", reason));
            log(r.code(), version, "CREATE", actor, detail("config", spec.config(), "reason", reason));
            return version(r.code(), version);
        });
    }

    public RuleVersion updateDraft(String actor, String code, int version, DraftRequest r) {
        String reason = required(r.reason(), "reason", 1000);
        RuleSpec spec = checked(code, r.name(), r.description(), r.template(), r.suppressDays(), r.config());
        return tx.execute(s -> {
            RuleVersion cur = lock(code, version);
            if (!cur.status().equals("DRAFT")) {
                throw new WorkflowException("Only a DRAFT can be edited; version " + version + " is " + cur.status());
            }
            if (!actor.equals(cur.authoredBy())) {
                throw new WorkflowException("Only the author (" + cur.authoredBy() + ") can edit this draft");
            }
            if (!cur.template().equals(spec.template())) {
                throw new IllegalArgumentException("the template of a rule cannot change");
            }
            jdbc.update("UPDATE " + rule + " SET name = :n, description = :d, suppress_days = :sd, config = CAST(:cfg AS jsonb), change_reason = :reason"
                    + " WHERE rule_code = :c AND version = :v", params(spec, version).addValue("reason", reason));
            log(code, version, "EDIT", actor, detail("before", cur.config(), "config", spec.config(), "reason", reason));
            return version(code, version);
        });
    }

    // ---- workflow ----

    public RuleVersion submit(String actor, String code, int version, String note) {
        return tx.execute(s -> {
            RuleVersion cur = lock(code, version);
            if (!cur.status().equals("DRAFT")) {
                throw new WorkflowException("Only a DRAFT can be submitted; version " + version + " is " + cur.status());
            }
            if (!actor.equals(cur.authoredBy())) {
                throw new WorkflowException("Only the author (" + cur.authoredBy() + ") can submit this draft");
            }
            loader.validate(spec(cur));          // still valid under the current templates
            jdbc.update("UPDATE " + rule + " SET status = 'PENDING_APPROVAL', submitted_by = :a, submitted_ts = CURRENT_TIMESTAMP WHERE rule_code = :c AND version = :v",
                    new MapSqlParameterSource().addValue("a", actor).addValue("c", code).addValue("v", version));
            log(code, version, "SUBMIT", actor, detail("note", note));
            return version(code, version);
        });
    }

    public RuleVersion approve(String actor, String code, int version, String note) {
        return tx.execute(s -> {
            RuleVersion cur = lock(code, version);
            if (!cur.status().equals("PENDING_APPROVAL")) {
                throw new WorkflowException("Only a version pending approval can be approved; version " + version + " is " + cur.status());
            }
            if (actor.equals(cur.submittedBy())) {
                throw new WorkflowException("Four-eyes: the person who submitted a version cannot approve it");
            }
            if (!dryRunCurrent(code, version)) {
                throw new WorkflowException("Run a dry-run of version " + version + " (after its last edit) before approving it");
            }
            loader.validate(spec(cur));
            jdbc.update("UPDATE " + rule + " SET status = 'RETIRED' WHERE rule_code = :c AND status = 'ACTIVE'", Map.of("c", code));
            jdbc.update("UPDATE " + rule + " SET status = 'ACTIVE', decided_by = :a, decided_ts = CURRENT_TIMESTAMP, decision_note = :n WHERE rule_code = :c AND version = :v",
                    new MapSqlParameterSource().addValue("a", actor).addValue("n", note).addValue("c", code).addValue("v", version));
            log(code, version, "APPROVE", actor, detail("note", note));
            return version(code, version);
        });
    }

    public RuleVersion reject(String actor, String code, int version, String note) {
        String why = required(note, "note", 1000);
        return tx.execute(s -> {
            RuleVersion cur = lock(code, version);
            if (!cur.status().equals("PENDING_APPROVAL")) {
                throw new WorkflowException("Only a version pending approval can be rejected; version " + version + " is " + cur.status());
            }
            if (actor.equals(cur.submittedBy())) {
                throw new WorkflowException("Four-eyes: the person who submitted a version cannot decide on it");
            }
            jdbc.update("UPDATE " + rule + " SET status = 'REJECTED', decided_by = :a, decided_ts = CURRENT_TIMESTAMP, decision_note = :n WHERE rule_code = :c AND version = :v",
                    new MapSqlParameterSource().addValue("a", actor).addValue("n", why).addValue("c", code).addValue("v", version));
            log(code, version, "REJECT", actor, detail("note", why));
            return version(code, version);
        });
    }

    public RuleVersion retire(String actor, String code, int version, String reason) {
        String why = required(reason, "reason", 1000);
        return tx.execute(s -> {
            RuleVersion cur = lock(code, version);
            if (!cur.status().equals("ACTIVE")) {
                throw new WorkflowException("Only an ACTIVE version can be retired; version " + version + " is " + cur.status());
            }
            jdbc.update("UPDATE " + rule + " SET status = 'RETIRED' WHERE rule_code = :c AND version = :v", Map.of("c", code, "v", version));
            log(code, version, "RETIRE", actor, detail("reason", why));
            return version(code, version);
        });
    }

    // ---- helpers ----

    /** Records an audit entry (also used by the dry-run). */
    public void log(String code, Integer version, String action, String actor, JsonNode detail) {
        jdbc.update("INSERT INTO " + audit + " (rule_code, version, action, actor, detail) VALUES (:c, :v, :a, :actor, CAST(:d AS jsonb))",
                new MapSqlParameterSource().addValue("c", code).addValue("v", version).addValue("a", action).addValue("actor", actor).addValue("d", detail.toString()));
    }

    private boolean dryRunCurrent(String code, int version) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COALESCE(MAX(audit_id) FILTER (WHERE action = 'DRYRUN'), 0)"
                + " > COALESCE(MAX(audit_id) FILTER (WHERE action IN ('CREATE', 'EDIT')), 0) FROM " + audit + " WHERE rule_code = :c AND version = :v",
                Map.of("c", code, "v", version), Boolean.class));
    }

    private RuleVersion lock(String code, int version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + rule + " WHERE rule_code = :c AND version = :v FOR UPDATE", Map.of("c", code, "v", version), RuleAdminService::map)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("No version " + version + " of rule '" + code + "'"));
    }

    private RuleSpec checked(String code, String name, String description, String template, Integer suppressDays, JsonNode config) {
        String n = required(name, "name", 200);
        if (description != null && description.length() > 2000) {
            throw new IllegalArgumentException("description is longer than 2000 characters");
        }
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("template is required");
        }
        int sd = suppressDays == null ? 0 : suppressDays;
        if (sd < 0 || sd > 365) {
            throw new IllegalArgumentException("suppress_days must be between 0 and 365");
        }
        JsonNode cfg;
        try {
            cfg = config == null ? JSON.createObjectNode() : JSON.readTree(JSON.writeValueAsString(config));   // same normalisation as the YAML loader
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("config is not valid JSON");
        }
        RuleSpec spec = new RuleSpec(code, n, description, template, "DRAFT", sd, cfg, "UI");
        loader.validate(spec);
        return spec;
    }

    private static RuleSpec spec(RuleVersion v) {
        return new RuleSpec(v.ruleCode(), v.name(), v.description(), v.template(), "DRAFT", v.suppressDays(), v.config(), "UI");
    }

    private static MapSqlParameterSource params(RuleSpec s, int version) {
        return new MapSqlParameterSource().addValue("c", s.code()).addValue("v", version).addValue("n", s.name()).addValue("d", s.description())
                .addValue("t", s.template()).addValue("sd", s.suppressDays()).addValue("cfg", s.config().toString());
    }

    private static String required(String value, String what, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(what + " is longer than " + max + " characters");
        }
        return value.trim();
    }

    private static ObjectNode detail(Object... kv) {
        ObjectNode n = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof JsonNode j) {
                n.set((String) kv[i], j);
            } else {
                n.put((String) kv[i], v == null ? null : v.toString());
            }
        }
        return n;
    }
}
