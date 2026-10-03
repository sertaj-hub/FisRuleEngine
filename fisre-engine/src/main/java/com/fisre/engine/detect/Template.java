package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * A generic, tested detection pattern. Turns a rule's JSON config into set-based SQL (ADR-0004).
 * Config values reach SQL only as bound parameters.
 */
public interface Template {

    /** What a template produces for one business date. */
    record Built(String hitsSql, String evidenceSql, Map<String, Object> params) {}

    /**
     * One configuration key for form generation (ADR-0010). {@code kind} is INT, DECIMAL, TEXT, ENUM, FILTER or PRODUCTS;
     * {@code options} lists the allowed values of an ENUM.
     */
    record Field(String key, String label, String kind, boolean required, String help, java.util.List<String> options) {
        public static Field of(String key, String label, String kind, boolean required, String help) {
            return new Field(key, label, kind, required, help, java.util.List.of());
        }
    }

    String code();

    /** The keys this template accepts, described for the rule editor. Must match what {@link #validate} accepts. */
    default java.util.List<Field> fields() {
        return java.util.List.of();
    }

    /** Throws IllegalArgumentException with a clear message if the config is not valid for this template. */
    void validate(JsonNode config);

    /**
     * {@code hitsSql} returns account_id, customer_id, product_type, evidence (jsonb) - one row per account that hits.
     * {@code evidenceSql} returns account_id, transaction_id, posting_date for the transactions behind the hits and selects
     * from the placeholder {@code {hits}}, which the caller replaces with the hits query as a subquery named h.
     */
    Built build(JsonNode config, LocalDate businessDate, String mst);

    /** Variant for templates that also read aml tables. Defaults to the master-only build. */
    default Built build(JsonNode config, LocalDate businessDate, String mst, String aml) {
        return build(config, businessDate, mst);
    }

    /**
     * Data the template depends on that is produced outside detection (e.g. ML scores). Throws IllegalStateException when it
     * is missing for the as-of day, so the rule fails visibly instead of silently finding nothing (REQ-ML-010).
     */
    default void requireInputs(JsonNode config, LocalDate asOf, NamedParameterJdbcTemplate jdbc, String aml) {}
}
