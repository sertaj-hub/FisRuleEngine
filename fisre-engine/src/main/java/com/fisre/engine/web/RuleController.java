package com.fisre.engine.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fisre.engine.detect.Template;
import com.fisre.engine.rules.DryRunService;
import com.fisre.engine.rules.RuleAdminService;
import com.fisre.engine.rules.RuleLoader;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API of the rule UI. Access rules are in {@link SecurityConfig}; workflow rules in {@link RuleAdminService}. */
@RestController
@RequestMapping("/api")
@ConditionalOnWebApplication
public class RuleController {

    public record Note(String note) {}

    public record DryRunRequest(String code, String template, JsonNode config, Integer days) {}

    private final RuleAdminService rules;
    private final DryRunService dryRuns;
    private final RuleLoader loader;
    private final JdbcTemplate jdbc;
    private final String mst;

    public RuleController(RuleAdminService rules, DryRunService dryRuns, RuleLoader loader, JdbcTemplate jdbc, com.fisre.engine.config.FisreProperties props) {
        this.rules = rules;
        this.dryRuns = dryRuns;
        this.loader = loader;
        this.jdbc = jdbc;
        this.mst = props.schemas().mst();
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return Map.of("username", auth.getName(), "roles", auth.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).sorted().toList());
    }

    /** Template field descriptions, from which the editor builds its forms (REQ-RUI-009). */
    @GetMapping("/templates")
    public Map<String, List<Template.Field>> templates() {
        Map<String, List<Template.Field>> out = new TreeMap<>();
        loader.templates().forEach((code, t) -> out.put(code, t.fields()));
        return out;
    }

    @GetMapping("/txn-types")
    public List<Map<String, Object>> txnTypes() {
        return jdbc.queryForList("SELECT txn_type, is_cash = 'Y' AS cash, description FROM " + mst + ".ref_txn_type ORDER BY txn_type");
    }

    @GetMapping("/rules")
    public List<RuleAdminService.RuleSummary> list() {
        return rules.list();
    }

    @GetMapping("/pending")
    public List<RuleAdminService.RuleVersion> pending() {
        return rules.pending();
    }

    @GetMapping("/rules/{code}/versions")
    public List<RuleAdminService.RuleVersion> versions(@PathVariable String code) {
        return rules.versions(code);
    }

    @GetMapping("/rules/{code}/audit")
    public List<RuleAdminService.AuditEntry> audit(@PathVariable String code) {
        return rules.auditTrail(code);
    }

    @PostMapping("/rules/{code}/versions")
    public RuleAdminService.RuleVersion create(@PathVariable String code, @RequestBody RuleAdminService.DraftRequest body, Principal who) {
        return rules.createDraft(who.getName(), new RuleAdminService.DraftRequest(code, body.name(), body.description(), body.template(), body.suppressDays(), body.config(), body.reason()));
    }

    @PutMapping("/rules/{code}/versions/{version}")
    public RuleAdminService.RuleVersion update(@PathVariable String code, @PathVariable int version, @RequestBody RuleAdminService.DraftRequest body, Principal who) {
        return rules.updateDraft(who.getName(), code, version, body);
    }

    @PostMapping("/rules/{code}/versions/{version}/submit")
    public RuleAdminService.RuleVersion submit(@PathVariable String code, @PathVariable int version, @RequestBody(required = false) Note body, Principal who) {
        return rules.submit(who.getName(), code, version, body == null ? null : body.note());
    }

    @PostMapping("/rules/{code}/versions/{version}/approve")
    public RuleAdminService.RuleVersion approve(@PathVariable String code, @PathVariable int version, @RequestBody(required = false) Note body, Principal who) {
        return rules.approve(who.getName(), code, version, body == null ? null : body.note());
    }

    @PostMapping("/rules/{code}/versions/{version}/reject")
    public RuleAdminService.RuleVersion reject(@PathVariable String code, @PathVariable int version, @RequestBody Note body, Principal who) {
        return rules.reject(who.getName(), code, version, body.note());
    }

    @PostMapping("/rules/{code}/versions/{version}/retire")
    public RuleAdminService.RuleVersion retire(@PathVariable String code, @PathVariable int version, @RequestBody Note body, Principal who) {
        return rules.retire(who.getName(), code, version, body.note());
    }

    @PostMapping("/rules/{code}/versions/{version}/dry-run")
    public DryRunService.Result dryRunVersion(@PathVariable String code, @PathVariable int version, @RequestParam(defaultValue = "7") int days, Principal who) {
        return dryRuns.runVersion(who.getName(), code, version, days);
    }

    @PostMapping("/dry-run")
    public DryRunService.Result dryRun(@RequestBody DryRunRequest body, Principal who) {
        return dryRuns.runAdHoc(who.getName(), body.code(), body.template(), body.config(), body.days() == null ? 7 : body.days());
    }
}
