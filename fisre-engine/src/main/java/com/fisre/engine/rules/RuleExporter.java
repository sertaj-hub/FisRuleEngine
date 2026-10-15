package com.fisre.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Writes the rules in the database back to YAML so a UI change can be committed to git (ADR-0010, REQ-RUI-011). */
@Service
public class RuleExporter {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private final RuleAdminService rules;

    public RuleExporter(RuleAdminService rules) {
        this.rules = rules;
    }

    /** Exports each rule's current version: the ACTIVE one, else the latest DRAFT or RETIRED one. Returns the files written. */
    public List<Path> export(Path dir) {
        try {
            Files.createDirectories(dir);
            List<Path> written = new java.util.ArrayList<>();
            for (RuleAdminService.RuleSummary s : rules.list()) {
                RuleAdminService.RuleVersion v = current(s.code());
                if (v == null) {
                    continue;
                }
                Path file = dir.resolve(v.ruleCode() + ".yml");
                ObjectNode out = YAML.createObjectNode();
                out.put("code", v.ruleCode());
                out.put("name", v.name());
                if (v.description() != null) {
                    out.put("description", v.description());
                }
                out.put("template", v.template());
                out.put("status", v.status().equals("ACTIVE") ? "ACTIVE" : v.status().equals("RETIRED") ? "RETIRED" : "DRAFT");
                out.put("suppress_days", v.suppressDays());
                out.set("config", v.config());
                if (Files.exists(file)) {                       // the scenarios live in the file, not in the database
                    JsonNode tests = YAML.readTree(file.toFile()).get("tests");
                    if (tests != null) {
                        out.set("tests", tests);
                    }
                }
                Files.writeString(file, YAML.writeValueAsString(out));
                written.add(file);
            }
            return written;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot export rules to " + dir + ": " + e.getMessage(), e);
        }
    }

    private RuleAdminService.RuleVersion current(String code) {
        Map<String, RuleAdminService.RuleVersion> byStatus = new LinkedHashMap<>();
        for (RuleAdminService.RuleVersion v : rules.versions(code)) {          // newest first
            byStatus.putIfAbsent(v.status(), v);
        }
        if (byStatus.containsKey("ACTIVE")) {
            return byStatus.get("ACTIVE");
        }
        RuleAdminService.RuleVersion draft = byStatus.get("DRAFT");
        RuleAdminService.RuleVersion retired = byStatus.get("RETIRED");
        if (draft != null && (retired == null || draft.version() > retired.version())) {
            return draft;
        }
        return retired != null ? retired : draft;
    }
}
