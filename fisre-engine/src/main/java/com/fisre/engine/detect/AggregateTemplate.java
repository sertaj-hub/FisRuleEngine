package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Matching transactions in a window reach a minimum count and a minimum total. */
@Component
public class AggregateTemplate extends TemplateSupport {

    @Override public String code() { return "AGGREGATE"; }

    @Override protected Set<String> allowedKeys() { return Set.of("window_days", "filter", "min_count", "min_sum"); }

    @Override
    protected void validateTemplate(JsonNode c) {
        posInt(c, "window_days");
        filter(c, "filter", false);
        if (optInt(c, "min_count", 1) <= 1 && decimal(c, "min_sum", BigDecimal.ZERO).signum() == 0) {
            throw new IllegalArgumentException("needs 'min_count' above 1 or 'min_sum' above 0");
        }
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst) {
        Map<String, Object> params = new HashMap<>();
        int window = posInt(c, "window_days");
        params.put("end", date(d));
        params.put("start", date(d.minusDays(window - 1L)));
        params.put("window_days", window);
        params.put("min_count", optInt(c, "min_count", 1));
        params.put("min_sum", decimal(c, "min_sum", BigDecimal.ZERO));
        String f = filter(c, "filter", false).sql("t", "f_", params, mst);
        String hits = "SELECT a.account_id, a.primary_customer_id AS customer_id, a.product_type, jsonb_build_object("
                + "'window_days', CAST(:window_days AS integer), 'txn_count', COUNT(*), 'total', SUM(t.amount), "
                + "'min_count', CAST(:min_count AS integer), 'min_sum', CAST(:min_sum AS numeric)) AS evidence "
                + "FROM " + mst + ".txn t JOIN " + mst + ".account a ON a.account_id = t.account_id "
                + "WHERE " + productClause(c, params) + " AND " + f + " AND t.posting_date BETWEEN :start AND :end "
                + "GROUP BY a.account_id, a.primary_customer_id, a.product_type "
                + "HAVING COUNT(*) >= :min_count AND SUM(t.amount) >= :min_sum";
        String evidence = "SELECT t.account_id, t.transaction_id FROM " + mst + ".txn t "
                + "WHERE t.account_id IN (SELECT h.account_id FROM {hits}) AND " + f + " AND t.posting_date BETWEEN :start AND :end";
        return new Built(hits, evidence, params);
    }
}
