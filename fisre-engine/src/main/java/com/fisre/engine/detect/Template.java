package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Map;

/**
 * A generic, tested detection pattern. Turns a rule's JSON config into set-based SQL (ADR-0004).
 * Config values reach SQL only as bound parameters.
 */
public interface Template {

    /** What a template produces for one business date. */
    record Built(String hitsSql, String evidenceSql, Map<String, Object> params) {}

    String code();

    /** Throws IllegalArgumentException with a clear message if the config is not valid for this template. */
    void validate(JsonNode config);

    /**
     * {@code hitsSql} returns account_id, customer_id, product_type, evidence (jsonb) - one row per account that hits.
     * {@code evidenceSql} returns account_id, transaction_id, posting_date for the transactions behind the hits and selects
     * from the placeholder {@code {hits}}, which the caller replaces with the hits query as a subquery named h.
     */
    Built build(JsonNode config, LocalDate businessDate, String mst);
}
