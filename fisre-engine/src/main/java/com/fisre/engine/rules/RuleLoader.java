package com.fisre.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.detect.Template;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads rule specs, validates every one, and stores new versions in aml.rule (specs/requirements/rules.md). */
@Service
public class RuleLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleLoader.class);
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "RETIRED");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, Template> templates = new HashMap<>();
    private final String rule;

    public RuleLoader(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, List<Template> templates, FisreProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        templates.forEach(t -> this.templates.put(t.code(), t));
        this.rule = props.schemas().aml() + ".rule";
    }

    public Map<String, Template> templates() {
        return templates;
    }

    public record LoadResult(int created, int unchanged) {}

    /** Loads every *.yml in the directory. All specs are validated before anything is written. */
    public LoadResult load(Path dir) {
        return load(readAll(dir), dir.toString());
    }

    /** Validates all specs, then stores new versions in one transaction. */
    public LoadResult load(List<RuleSpec> specs, String source) {
        for (RuleSpec s : specs) {
            validate(s);
        }
        return tx.execute(status -> {
            int created = 0;
            for (RuleSpec s : specs) {
                created += store(s) ? 1 : 0;
            }
            log.info("Rules loaded from {}: {} new version(s), {} unchanged", source, created, specs.size() - created);
            return new LoadResult(created, specs.size() - created);
        });
    }

    public static List<RuleSpec> readAll(Path dir) {
        List<RuleSpec> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                out.add(read(f));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read rules directory " + dir + ": " + e.getMessage(), e);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("No rule specs (*.yml) found in " + dir);
        }
        return out;
    }

    public static RuleSpec read(Path f) {
        try {
            JsonNode n = YAML.readTree(f.toFile());
            String code = text(n, "code");
            return new RuleSpec(code, text(n, "name"), n.path("description").asText(null), text(n, "template"),
                    n.has("status") ? n.get("status").asText() : "DRAFT", n.path("suppress_days").asInt(0),
                    // round-trip so numbers compare the same as values read back from the database
                    JSON.readTree(JSON.writeValueAsString(n.path("config"))), f.getFileName().toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse rule spec " + f.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String key) {
        if (!n.hasNonNull(key) || n.get(key).asText().isBlank()) {
            throw new IllegalArgumentException("rule spec is missing '" + key + "'");
        }
        return n.get(key).asText();
    }

    public void validate(RuleSpec s) {
        try {
            if (!STATUSES.contains(s.status())) {
                throw new IllegalArgumentException("status must be DRAFT, ACTIVE or RETIRED");
            }
            if (s.suppressDays() < 0) {
                throw new IllegalArgumentException("suppress_days must not be negative");
            }
            Template t = templates.get(s.template());
            if (t == null) {
                throw new IllegalArgumentException("unknown template '" + s.template() + "' (known: " + new java.util.TreeSet<>(templates.keySet()) + ")");
            }
            t.validate(s.config());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rule " + s.code() + " (" + s.sourceFile() + "): " + e.getMessage(), e);
        }
    }

    /** Returns true if a new version was written. */
    private boolean store(RuleSpec s) {
        List<Map<String, Object>> latest = jdbc.queryForList("SELECT version, name, description, template_code, status, suppress_days, CAST(config AS text) AS config, source"
                + " FROM " + rule + " WHERE rule_code = :c AND version = (SELECT MAX(version) FROM " + rule + " WHERE rule_code = :c)",
                Map.of("c", s.code()));
        int version = 1;
        if (!latest.isEmpty()) {
            Map<String, Object> l = latest.get(0);
            version = ((Number) l.get("version")).intValue();
            if (same(s, l)) {
                return false;
            }
            if ("UI".equals(l.get("source"))) {
                // the database is ahead of the file: someone changed this rule through the UI (ADR-0010, REQ-RUI-010)
                log.warn("Rule {}: the latest version {} was created in the UI and differs from {}; the file is NOT loaded. Run export-rules to bring the file up to date.",
                        s.code(), version, s.sourceFile());
                return false;
            }
            version++;
        }
        jdbc.update("UPDATE " + rule + " SET status = 'RETIRED' WHERE rule_code = :c AND status = 'ACTIVE'", Map.of("c", s.code()));
        String json;
        try {
            json = JSON.writeValueAsString(s.config());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("INSERT INTO " + rule + " (rule_code, version, name, description, template_code, status, suppress_days, config)"
                + " VALUES (:c, :v, :n, :d, :t, :s, :sd, CAST(:cfg AS jsonb))",
                new MapSqlParameterSource().addValue("c", s.code()).addValue("v", version).addValue("n", s.name())
                        .addValue("d", s.description()).addValue("t", s.template()).addValue("s", s.status())
                        .addValue("sd", s.suppressDays()).addValue("cfg", json));
        return true;
    }

    private static boolean same(RuleSpec s, Map<String, Object> l) {
        try {
            return s.name().equals(l.get("name")) && java.util.Objects.equals(s.description(), l.get("description"))
                    && s.template().equals(l.get("template_code")) && s.status().equals(l.get("status"))
                    && s.suppressDays() == ((Number) l.get("suppress_days")).intValue()
                    && JSON.readTree((String) l.get("config")).equals(s.config());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
