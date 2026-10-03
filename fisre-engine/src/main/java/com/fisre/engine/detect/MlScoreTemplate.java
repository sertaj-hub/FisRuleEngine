package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Accounts whose anomaly score from the ACTIVE ML model on the as-of day reaches a minimum (ADR-0009). The scores are
 * written by fisre-ml into aml.ml_score; this template only reads them, so ML alerts follow the same path as every other.
 */
@Component
public class MlScoreTemplate extends TemplateSupport {

    private static final String DEFAULT_MODEL = "account_anomaly";

    @Override public String code() { return "ML_SCORE"; }

    @Override protected Set<String> allowedKeys() { return Set.of("model", "min_score"); }

    @Override
    protected void validateTemplate(JsonNode c) {
        BigDecimal min = decimal(c, "min_score", null);
        if (min.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("'min_score' must be between 0 and 1");
        }
        model(c);
    }

    private static String model(JsonNode c) {
        JsonNode m = c.get("model");
        if (m == null) {
            return DEFAULT_MODEL;
        }
        if (!m.isTextual() || m.asText().isBlank()) {
            throw new IllegalArgumentException("'model' must be a model name");
        }
        return m.asText();
    }

    @Override
    public void requireInputs(JsonNode c, LocalDate asOf, NamedParameterJdbcTemplate jdbc, String aml) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + aml + ".ml_score s JOIN " + aml + ".ml_model m ON m.model_version = s.model_version"
                + " WHERE m.status = 'ACTIVE' AND m.model_name = :m AND s.as_of_date = :d",
                Map.of("m", model(c), "d", date(asOf)), Long.class);
        if (n == null || n == 0) {
            throw new IllegalStateException("No ML scores for model '" + model(c) + "' on posting day " + asOf
                    + ": run the scoring job (fisre-ml score) before detection");
        }
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst) {
        throw new UnsupportedOperationException("ML_SCORE needs the aml schema; use build(config, date, mst, aml)");
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst, String aml) {
        Map<String, Object> params = new HashMap<>();
        params.put("end", date(d));
        params.put("ml_model", model(c));
        params.put("min_score", decimal(c, "min_score", null));
        String hits = "SELECT a.account_id, a.primary_customer_id AS customer_id, a.product_type, jsonb_build_object("
                + "'model', m.model_name, 'model_version', s.model_version, 'score', s.score, 'rank', s.rank_in_product, "
                + "'top_features', s.explanation, 'min_score', CAST(:min_score AS numeric)) AS evidence "
                + "FROM " + aml + ".ml_score s JOIN " + aml + ".ml_model m ON m.model_version = s.model_version AND m.status = 'ACTIVE' "
                + "AND m.model_name = :ml_model JOIN " + mst + ".account a ON a.account_id = s.account_id "
                + "WHERE s.as_of_date = :end AND s.score >= :min_score AND " + productClause(c, params);
        String evidence = "SELECT t.account_id, t.transaction_id, t.posting_date FROM " + mst + ".txn t "
                + "WHERE t.account_id IN (SELECT h.account_id FROM {hits}) AND t.posting_date = :end";
        return new Built(hits, evidence, params);
    }
}
