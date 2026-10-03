package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Inflow reaches a minimum and outflow is at least a percentage of it, within one window. */
@Component
public class FlowThroughTemplate extends TemplateSupport {

    @Override public String code() { return "FLOW_THROUGH"; }

    @Override
    public java.util.List<Field> fields() {
        return java.util.List.of(
                Field.of("window_days", "Window (days)", "INT", true, "Look back this many days"),
                Field.of("in", "Money in", "FILTER", true, "Transactions counted as money coming in"),
                Field.of("out", "Money out", "FILTER", true, "Transactions counted as money going out"),
                Field.of("min_in", "Minimum amount in", "DECIMAL", true, "Total in must reach this"),
                Field.of("min_out_pct", "Minimum percent moved out", "DECIMAL", true, "Out as a percentage of in, e.g. 80"),
                Field.of("product_types", "Products", "PRODUCTS", false, "Limit the rule to these products; empty means all"));
    }

    @Override protected Set<String> allowedKeys() { return Set.of("window_days", "in", "out", "min_in", "min_out_pct"); }

    @Override
    protected void validateTemplate(JsonNode c) {
        posInt(c, "window_days");
        filter(c, "in", true);
        filter(c, "out", true);
        decimal(c, "min_in", null);
        decimal(c, "min_out_pct", null);
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst) {
        Map<String, Object> params = new HashMap<>();
        int window = posInt(c, "window_days");
        params.put("end", date(d));
        params.put("start", date(d.minusDays(window - 1L)));
        params.put("window_days", window);
        params.put("min_in", decimal(c, "min_in", null));
        params.put("min_out_pct", decimal(c, "min_out_pct", null));
        String in = filter(c, "in", true).sql("t", "in_", params, mst);
        String out = filter(c, "out", true).sql("t", "out_", params, mst);
        String candidates = "SELECT c.account_id FROM " + mst + ".txn c WHERE c.posting_date = :end AND (("
                + filter(c, "in", true).sql("c", "in_", params, mst) + ") OR ("
                + filter(c, "out", true).sql("c", "out_", params, mst) + "))";
        String hits = "SELECT a.account_id, a.primary_customer_id AS customer_id, a.product_type, jsonb_build_object("
                + "'window_days', CAST(:window_days AS integer), 'total_in', i.amt, 'total_out', o.amt, "
                + "'out_pct', ROUND(o.amt * 100 / i.amt, 1), 'min_in', CAST(:min_in AS numeric), 'min_out_pct', CAST(:min_out_pct AS numeric)) AS evidence "
                + "FROM " + mst + ".account a "
                + "JOIN (SELECT t.account_id, SUM(t.amount) AS amt FROM " + mst + ".txn t WHERE " + in
                + " AND t.posting_date BETWEEN :start AND :end GROUP BY t.account_id) i ON i.account_id = a.account_id "
                + "JOIN (SELECT t.account_id, SUM(t.amount) AS amt FROM " + mst + ".txn t WHERE " + out
                + " AND t.posting_date BETWEEN :start AND :end GROUP BY t.account_id) o ON o.account_id = a.account_id "
                + "WHERE " + productClause(c, params) + " AND a.account_id IN (" + candidates + ") AND i.amt >= :min_in AND o.amt * 100 >= i.amt * :min_out_pct";
        String evidence = "SELECT t.account_id, t.transaction_id, t.posting_date FROM " + mst + ".txn t "
                + "WHERE t.account_id IN (SELECT h.account_id FROM {hits}) AND t.posting_date BETWEEN :start AND :end "
                + "AND ((" + in + ") OR (" + out + "))";
        return new Built(hits, evidence, params);
    }
}
