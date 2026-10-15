package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** An old account with no transactions for N days transacts above a minimum on the business date. */
@Component
public class DormantReactivationTemplate extends TemplateSupport {

    @Override public String code() { return "DORMANT_REACTIVATION"; }

    @Override
    public java.util.List<Field> fields() {
        return java.util.List.of(
                Field.of("dormant_days", "Dormant for (days)", "INT", true, "No transactions for this long"),
                Field.of("filter", "Matching transactions", "FILTER", false, "Which activity counts as reactivation"),
                Field.of("min_total", "Minimum total today", "DECIMAL", true, "Activity on the day must reach this"),
                Field.of("product_types", "Products", "PRODUCTS", false, "Limit the rule to these products; empty means all"));
    }

    @Override protected Set<String> allowedKeys() { return Set.of("dormant_days", "filter", "min_total"); }

    @Override
    protected void validateTemplate(JsonNode c) {
        posInt(c, "dormant_days");
        filter(c, "filter", false);
        decimal(c, "min_total", null);
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst) {
        Map<String, Object> params = new HashMap<>();
        int days = posInt(c, "dormant_days");
        params.put("end", date(d));
        params.put("dormant_start", date(d.minusDays(days)));
        params.put("dormant_days", days);
        params.put("min_total", decimal(c, "min_total", null));
        String f = filter(c, "filter", false).sql("t", "f_", params, mst);
        String hits = "SELECT a.account_id, a.primary_customer_id AS customer_id, a.product_type, jsonb_build_object("
                + "'dormant_days', CAST(:dormant_days AS integer), 'total', x.amt, "
                + "'last_activity', (SELECT MAX(p.posting_date) FROM " + mst + ".txn p WHERE p.account_id = a.account_id AND p.posting_date < :end)) AS evidence "
                + "FROM " + mst + ".account a "
                + "JOIN (SELECT t.account_id, SUM(t.amount) AS amt FROM " + mst + ".txn t WHERE " + f
                + " AND t.posting_date = :end GROUP BY t.account_id) x ON x.account_id = a.account_id "
                + "WHERE " + productClause(c, params) + " AND a.open_date < :dormant_start AND x.amt >= :min_total "
                + "AND NOT EXISTS (SELECT 1 FROM " + mst + ".txn p WHERE p.account_id = a.account_id "
                + "AND p.posting_date >= :dormant_start AND p.posting_date < :end)";
        String evidence = "SELECT t.account_id, t.transaction_id, t.posting_date FROM " + mst + ".txn t "
                + "WHERE t.account_id IN (SELECT h.account_id FROM {hits}) AND " + f + " AND t.posting_date = :end";
        return new Built(hits, evidence, params);
    }
}
