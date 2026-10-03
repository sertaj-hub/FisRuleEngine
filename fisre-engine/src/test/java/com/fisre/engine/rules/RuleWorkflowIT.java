package com.fisre.engine.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.spec.Req;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The approval workflow behind the rule UI (ADR-0010). */
@SpringBootTest
class RuleWorkflowIT {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleLoader loader;
    @Autowired RuleAdminService admin;
    @Autowired DryRunService dryRuns;
    @Autowired RuleExporter exporter;
    @TempDir Path tmp;

    String aml;
    Fixtures fx;

    @BeforeEach
    void reset() throws IOException {
        aml = props.schemas().aml();
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
        fx.liveBatch("B-1", "2026-10-01");
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        fx.txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "6000.00", "2026-09-30");
        fx.txn("T2", "A1", "CASH_DEPOSIT", "CREDIT", "5000.00", "2026-09-30");
        fx.account("A2", "DEPOSIT", "C2", "2020-01-01");
        fx.txn("T3", "A2", "CASH_DEPOSIT", "CREDIT", "6000.00", "2026-09-30");
    }

    private RuleAdminService.DraftRequest request(String code, double minSum) throws IOException {
        return new RuleAdminService.DraftRequest(code, "Cash in a day", "test rule", "AGGREGATE", 0,
                JSON.readTree("{\"window_days\":1,\"min_sum\":" + minSum + ",\"filter\":{\"cash\":true}}"), "initial thresholds");
    }

    private void activateFromYaml(String code, double minSum) throws IOException {
        Files.writeString(tmp.resolve(code + ".yml"), "code: " + code + "\nname: Cash in a day\ntemplate: AGGREGATE\nstatus: ACTIVE\nconfig:\n  window_days: 1\n  min_sum: " + minSum
                + "\n  filter: {cash: true}\ntests:\n  - {name: x, as_of: 2026-09-30, accounts: [], txns: [], expect_alerts: []}\n");
        loader.load(tmp);
    }

    /** Draft, dry-run, submit by alice, approve by bob. Returns the approved version. */
    private RuleAdminService.RuleVersion approvedVersion(String code, double minSum) throws IOException {
        RuleAdminService.RuleVersion v = admin.createDraft("alice", request(code, minSum));
        dryRuns.runVersion("alice", code, v.version(), 3);
        admin.submit("alice", code, v.version(), "please approve");
        return admin.approve("bob", code, v.version(), "ok");
    }

    @Test
    @Req("REQ-RUI-001")
    void aDraftStartsAsUiSourcedAndIsOnlySavedWhenTheTemplateAcceptsIt() throws IOException {
        RuleAdminService.RuleVersion v = admin.createDraft("alice", request("UI_RULE", 10000));
        assertThat(v.status()).isEqualTo("DRAFT");
        assertThat(v.source()).isEqualTo("UI");
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.authoredBy()).isEqualTo("alice");

        assertThatThrownBy(() -> admin.createDraft("alice", new RuleAdminService.DraftRequest("BAD_RULE", "x", null, "AGGREGATE", 0,
                JSON.readTree("{\"window_days\":1,\"surprise\":3}"), "r"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown config key");
        assertThatThrownBy(() -> admin.createDraft("alice", new RuleAdminService.DraftRequest("bad", "x", null, "AGGREGATE", 0, JSON.readTree("{}"), "r")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code must be");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".rule WHERE rule_code = 'BAD_RULE'", Long.class)).isZero();
        // a second draft while one is open is refused; the author alone edits it
        assertThatThrownBy(() -> admin.createDraft("alice", request("UI_RULE", 9000))).isInstanceOf(WorkflowException.class);
        assertThatThrownBy(() -> admin.updateDraft("mallory", "UI_RULE", 1, request("UI_RULE", 9000))).isInstanceOf(WorkflowException.class).hasMessageContaining("author");
    }

    @Test
    @Req({"REQ-RUI-002", "REQ-RUI-006"})
    void onlyTheDefinedTransitionsAreAllowedAndEachStepIsAudited() throws IOException {
        RuleAdminService.RuleVersion v = admin.createDraft("alice", request("FLOW", 10000));
        assertThatThrownBy(() -> admin.approve("bob", "FLOW", 1, "x")).isInstanceOf(WorkflowException.class);       // not submitted
        assertThatThrownBy(() -> admin.retire("bob", "FLOW", 1, "x")).isInstanceOf(WorkflowException.class);        // not active
        admin.submit("alice", "FLOW", 1, "n");
        assertThatThrownBy(() -> admin.updateDraft("alice", "FLOW", 1, request("FLOW", 1))).isInstanceOf(WorkflowException.class);   // no editing once submitted
        assertThatThrownBy(() -> admin.reject("bob", "FLOW", 1, " ")).isInstanceOf(IllegalArgumentException.class);                  // reason required
        RuleAdminService.RuleVersion rejected = admin.reject("bob", "FLOW", 1, "threshold too low");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> admin.submit("alice", "FLOW", 1, "again")).isInstanceOf(WorkflowException.class);

        RuleAdminService.RuleVersion approved = approvedVersion("FLOW", 9000);
        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.version()).isEqualTo(2);
        RuleAdminService.RuleVersion retired = admin.retire("bob", "FLOW", 2, "no longer needed");
        assertThat(retired.status()).isEqualTo("RETIRED");

        List<String> actions = admin.auditTrail("FLOW").stream().map(RuleAdminService.AuditEntry::action).toList();
        assertThat(actions).contains("CREATE", "SUBMIT", "REJECT", "DRYRUN", "APPROVE", "RETIRE");
        assertThat(admin.auditTrail("FLOW").get(0).actor()).isEqualTo("bob");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + aml + ".rule_audit SET actor = 'x'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + aml + ".rule_audit")).hasMessageContaining("append-only");
    }

    @Test
    @Req("REQ-RUI-003")
    void theSubmitterCanNeverApproveOrReject_serviceAndDatabase() throws IOException {
        RuleAdminService.RuleVersion v = admin.createDraft("alice", request("FOUR", 10000));
        dryRuns.runVersion("alice", "FOUR", 1, 3);
        admin.submit("alice", "FOUR", 1, "n");
        assertThatThrownBy(() -> admin.approve("alice", "FOUR", 1, "self")).isInstanceOf(WorkflowException.class).hasMessageContaining("Four-eyes");
        assertThatThrownBy(() -> admin.reject("alice", "FOUR", 1, "self")).isInstanceOf(WorkflowException.class).hasMessageContaining("Four-eyes");
        // even a direct database write cannot get around it
        assertThatThrownBy(() -> jdbc.update("UPDATE " + aml + ".rule SET status = 'ACTIVE', decided_by = 'alice' WHERE rule_code = 'FOUR'"))
                .hasMessageContaining("ck_rule_four_eyes");
        assertThat(admin.approve("bob", "FOUR", 1, "ok").decidedBy()).isEqualTo("bob");
    }

    @Test
    @Req("REQ-RUI-004")
    void approvalSwapsTheActiveVersionInOneStep() throws IOException {
        activateFromYaml("SWAP", 10000.01);
        assertThat(admin.versions("SWAP").get(0).status()).isEqualTo("ACTIVE");

        RuleAdminService.RuleVersion v2 = approvedVersion("SWAP", 5000);

        List<RuleAdminService.RuleVersion> all = admin.versions("SWAP");
        assertThat(all).extracting(RuleAdminService.RuleVersion::status).containsExactly("ACTIVE", "RETIRED");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".rule WHERE rule_code = 'SWAP' AND status = 'ACTIVE'", Long.class)).isEqualTo(1);
    }

    @Test
    @Req({"REQ-RUI-007", "REQ-RUI-008"})
    void aDryRunShowsTheEffectAgainstTheActiveVersionAndLeavesNothingBehind() throws IOException {
        activateFromYaml("DRY", 10000.01);                       // only A1 (11,000) qualifies
        RuleAdminService.RuleVersion v = admin.createDraft("alice", request("DRY", 5000));    // A2 (6,000) would qualify too
        admin.submit("alice", "DRY", v.version(), "n");
        assertThatThrownBy(() -> admin.approve("bob", "DRY", v.version(), "x")).isInstanceOf(WorkflowException.class).hasMessageContaining("dry-run");

        DryRunService.Result r = dryRuns.runVersion("alice", "DRY", v.version(), 2);

        assertThat(r.activeVersion()).isEqualTo(1);
        assertThat(r.perDay()).hasSize(2);
        DryRunService.Day day = r.perDay().get(0);
        assertThat(day.postingDay()).hasToString("2026-09-30");
        assertThat(day.proposed()).isEqualTo(2);
        assertThat(day.active()).isEqualTo(1);
        assertThat(day.added()).isEqualTo(1);
        assertThat(day.removed()).isZero();
        assertThat(r.sample()).hasSize(2);
        assertThatThrownBy(() -> dryRuns.runVersion("alice", "DRY", v.version(), 99)).isInstanceOf(IllegalArgumentException.class);
        // nothing was written: no alerts, no leftover temp tables
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".alert", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_class WHERE relname IN ('dr_new', 'dr_cur')", Long.class)).isZero();
        assertThat(admin.approve("bob", "DRY", v.version(), "ok").status()).isEqualTo("ACTIVE");

        // editing again invalidates the earlier dry-run
        RuleAdminService.RuleVersion v3 = admin.createDraft("alice", request("DRY", 4000));
        dryRuns.runVersion("alice", "DRY", v3.version(), 1);
        admin.updateDraft("alice", "DRY", v3.version(), request("DRY", 3000));
        admin.submit("alice", "DRY", v3.version(), "n");
        assertThatThrownBy(() -> admin.approve("bob", "DRY", v3.version(), "x")).isInstanceOf(WorkflowException.class).hasMessageContaining("dry-run");
    }

    @Test
    @Req("REQ-RUI-010")
    void loadRulesDoesNotOverwriteARuleChangedInTheUi() throws IOException {
        activateFromYaml("KEEP", 10000.01);
        approvedVersion("KEEP", 5000);                             // UI version 2 is now ACTIVE

        activateFromYaml("KEEP", 10000.01);                        // the (older) file is loaded again

        List<RuleAdminService.RuleVersion> all = admin.versions("KEEP");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).source()).isEqualTo("UI");
        assertThat(all.get(0).status()).isEqualTo("ACTIVE");
        assertThat(all.get(0).config().get("min_sum").asDouble()).isEqualTo(5000);
    }

    @Test
    @Req("REQ-RUI-011")
    void exportWritesTheCurrentVersionAndKeepsTheScenarios() throws IOException {
        activateFromYaml("EXP", 10000.01);                         // file with a tests: section
        approvedVersion("EXP", 5000);

        exporter.export(tmp);

        String yaml = Files.readString(tmp.resolve("EXP.yml"));
        assertThat(yaml).contains("code: EXP").contains("status: ACTIVE").contains("min_sum: 5000").contains("tests:").contains("expect_alerts");
        assertThat(RuleLoader.read(tmp.resolve("EXP.yml")).config().get("min_sum").asDouble()).isEqualTo(5000);
    }
}
