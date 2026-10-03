package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The as-of day's activity is a multiple of the account's own daily average over the previous N days.
 * Candidates are the accounts active on the as-of day; their baseline is read from the N prior partitions only.
 */
@Component
public class BaselineDeviationTemplate extends TemplateSupport {

    @Override public String code() { return "BASELINE_DEVIATION"; }

    @Override
    protected Set<String> allowedKeys() {
        return Set.of("baseline_days", "filter", "metric", "multiplier", "min_value", "min_active_days");
    }

    private static boolean sum(JsonNode c) {
        return "SUM".equals(c.get("metric").asText());
    }

    @Override
    protected void validateTemplate(JsonNode c) {
        posInt(c, "baseline_days");
        filter(c, "filter", false);
        if (!c.has("metric") || !Set.of("SUM", "COUNT").contains(c.get("metric").asText())) {
            throw new IllegalArgumentException("'metric' must be SUM or COUNT");
        }
        if (decimal(c, "multiplier", null).signum() == 0) {
            throw new IllegalArgumentException("'multiplier' must be above 0");
        }
        decimal(c, "min_value", BigDecimal.ZERO);
        optInt(c, "min_active_days", 1);
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst) {
        Map<String, Object> params = new HashMap<>();
        int days = posInt(c, "baseline_days");
        params.put("end", date(d));
        params.put("bstart", date(d.minusDays(days)));
        params.put("bend", date(d.minusDays(1)));
        params.put("baseline_days", days);
        params.put("multiplier", decimal(c, "multiplier", null));
        params.put("min_value", decimal(c, "min_value", BigDecimal.ZERO));
        params.put("min_active_days", optInt(c, "min_active_days", 1));
        String f = filter(c, "filter", false).sql("t", "f_", params, mst);
        String today = sum(c) ? "d.v" : "d.c";
        String base = sum(c) ? "b.s" : "b.c";
        String hits = "SELECT a.account_id, a.primary_customer_id AS customer_id, a.product_type, jsonb_build_object("
                + "'metric', '" + c.get("metric").asText() + "', 'today', " + today + ", 'baseline_daily_average', ROUND(" + base + " / :baseline_days, 2), "
                + "'ratio', ROUND(" + today + " * :baseline_days / " + base + ", 1), 'multiplier', CAST(:multiplier AS numeric), "
                + "'baseline_days', CAST(:baseline_days AS integer), 'baseline_active_days', b.days) AS evidence "
                + "FROM " + mst + ".account a "
                + "JOIN (SELECT t.account_id, SUM(t.amount) AS v, COUNT(*) AS c FROM " + mst + ".txn t WHERE " + f
                + " AND t.posting_date = :end GROUP BY t.account_id) d ON d.account_id = a.account_id "
                + "JOIN (SELECT t.account_id, SUM(t.amount) AS s, COUNT(*) AS c, COUNT(DISTINCT t.posting_date) AS days FROM " + mst + ".txn t WHERE " + f
                + " AND t.posting_date BETWEEN :bstart AND :bend AND t.account_id IN (SELECT x.account_id FROM " + mst + ".txn x WHERE "
                + filter(c, "filter", false).sql("x", "f_", params, mst) + " AND x.posting_date = :end) GROUP BY t.account_id) b ON b.account_id = a.account_id "
                + "WHERE " + productClause(c, params) + " AND b.days >= :min_active_days AND " + today + " >= :min_value"
                + " AND " + today + " * :baseline_days >= :multiplier * " + base;
        String evidence = "SELECT t.account_id, t.transaction_id, t.posting_date FROM " + mst + ".txn t "
                + "WHERE t.account_id IN (SELECT h.account_id FROM {hits}) AND " + f + " AND t.posting_date = :end";
        return new Built(hits, evidence, params);
    }
}
