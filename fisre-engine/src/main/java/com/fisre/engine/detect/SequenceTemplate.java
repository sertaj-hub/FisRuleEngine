package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** "Then" events on the business date preceded, within N days, by "first" events, with amount conditions. */
@Component
public class SequenceTemplate extends TemplateSupport {

    @Override public String code() { return "SEQUENCE"; }

    @Override
    protected Set<String> allowedKeys() {
        return Set.of("first", "then", "within_days", "first_min_total", "then_min_total", "then_min_pct_of_first");
    }

    @Override
    protected void validateTemplate(JsonNode c) {
        posInt(c, "within_days");
        filter(c, "first", true);
        filter(c, "then", true);
        decimal(c, "first_min_total", BigDecimal.ZERO);
        decimal(c, "then_min_total", BigDecimal.ZERO);
        decimal(c, "then_min_pct_of_first", BigDecimal.ZERO);
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst) {
        Map<String, Object> params = new HashMap<>();
        int within = posInt(c, "within_days");
        params.put("end", date(d));
        params.put("start", date(d.minusDays(within)));
        params.put("within_days", within);
        params.put("first_min", decimal(c, "first_min_total", BigDecimal.ZERO));
        params.put("then_min", decimal(c, "then_min_total", BigDecimal.ZERO));
        params.put("then_pct", decimal(c, "then_min_pct_of_first", BigDecimal.ZERO));
        String first = filter(c, "first", true).sql("f", "first_", params, mst);
        String then = filter(c, "then", true).sql("t", "then_", params, mst);
        String hits = "SELECT a.account_id, a.primary_customer_id AS customer_id, a.product_type, jsonb_build_object("
                + "'within_days', CAST(:within_days AS integer), 'first_total', SUM(f.amount), 'then_total', th.amt, "
                + "'then_pct_of_first', ROUND(th.amt * 100 / SUM(f.amount), 1)) AS evidence "
                + "FROM (SELECT t.account_id, SUM(t.amount) AS amt, MAX(t.txn_ts) AS max_ts FROM " + mst + ".txn t WHERE " + then
                + " AND t.posting_date = :end GROUP BY t.account_id) th "
                + "JOIN " + mst + ".account a ON a.account_id = th.account_id "
                + "JOIN " + mst + ".txn f ON f.account_id = th.account_id AND f.posting_date BETWEEN :start AND :end "
                + "AND f.txn_ts < th.max_ts AND " + first + " "
                + "WHERE " + productClause(c, params) + " "
                + "GROUP BY a.account_id, a.primary_customer_id, a.product_type, th.amt "
                + "HAVING SUM(f.amount) >= :first_min AND th.amt >= :then_min AND th.amt * 100 >= SUM(f.amount) * :then_pct";
        String firstT = filter(c, "first", true).sql("t", "first_", params, mst);
        String evidence = "SELECT t.account_id, t.transaction_id FROM " + mst + ".txn t "
                + "WHERE t.account_id IN (SELECT h.account_id FROM {hits}) AND t.posting_date BETWEEN :start AND :end "
                + "AND ((" + then + " AND t.posting_date = :end) OR (" + firstT + "))";
        return new Built(hits, evidence, params);
    }
}
