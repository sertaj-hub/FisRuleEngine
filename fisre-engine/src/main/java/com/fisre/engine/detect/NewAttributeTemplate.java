package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A matching transaction on the as-of day uses an attribute value (for example counterparty country) that the
 * account has not used in the previous N days. The attribute name comes from a whitelist, never from free text.
 */
@Component
public class NewAttributeTemplate extends TemplateSupport {

    private static final Set<String> ATTRIBUTES =
            Set.of("counterparty_country", "counterparty_account", "counterparty_name", "merchant_category_code", "channel");

    @Override public String code() { return "NEW_ATTRIBUTE"; }

    @Override
    protected Set<String> allowedKeys() {
        return Set.of("attribute", "baseline_days", "filter", "min_active_days");
    }

    private static String attribute(JsonNode c) {
        String a = c.hasNonNull("attribute") ? c.get("attribute").asText() : "";
        if (!ATTRIBUTES.contains(a)) {
            throw new IllegalArgumentException("'attribute' must be one of " + new java.util.TreeSet<>(ATTRIBUTES));
        }
        return a;
    }

    @Override
    protected void validateTemplate(JsonNode c) {
        attribute(c);
        posInt(c, "baseline_days");
        filter(c, "filter", false);
        optInt(c, "min_active_days", 1);
    }

    @Override
    public Built build(JsonNode c, LocalDate d, String mst) {
        Map<String, Object> params = new HashMap<>();
        String attr = attribute(c);
        int days = posInt(c, "baseline_days");
        params.put("end", date(d));
        params.put("bstart", date(d.minusDays(days)));
        params.put("bend", date(d.minusDays(1)));
        params.put("baseline_days", days);
        params.put("min_active_days", optInt(c, "min_active_days", 1));
        String f = filter(c, "filter", false).sql("t", "f_", params, mst);
        String fc = filter(c, "filter", false).sql("c", "f_", params, mst);
        String novel = "t." + attr + " IS NOT NULL AND TRIM(t." + attr + ") <> '' AND NOT EXISTS (SELECT 1 FROM " + mst + ".txn b WHERE b.account_id = t.account_id"
                + " AND b." + attr + " = t." + attr + " AND b.posting_date BETWEEN :bstart AND :bend)";
        // The account must have been active in the baseline (any transaction), looked up only for today's candidates.
        String active = "t.account_id IN (SELECT b2.account_id FROM " + mst + ".txn b2 WHERE b2.posting_date BETWEEN :bstart AND :bend"
                + " AND b2.account_id IN (SELECT c.account_id FROM " + mst + ".txn c WHERE " + fc + " AND c.posting_date = :end)"
                + " GROUP BY b2.account_id HAVING COUNT(DISTINCT b2.posting_date) >= :min_active_days)";
        String hits = "SELECT a.account_id, a.primary_customer_id AS customer_id, a.product_type, jsonb_build_object("
                + "'attribute', '" + attr + "', 'new_values', jsonb_agg(DISTINCT t." + attr + "), 'txn_count', COUNT(*), 'total', SUM(t.amount), "
                + "'baseline_days', CAST(:baseline_days AS integer)) AS evidence "
                + "FROM " + mst + ".txn t JOIN " + mst + ".account a ON a.account_id = t.account_id "
                + "WHERE " + productClause(c, params) + " AND " + f + " AND t.posting_date = :end AND " + novel + " AND " + active + " "
                + "GROUP BY a.account_id, a.primary_customer_id, a.product_type";
        String evidence = "SELECT t.account_id, t.transaction_id, t.posting_date FROM " + mst + ".txn t "
                + "WHERE t.account_id IN (SELECT h.account_id FROM {hits}) AND " + f + " AND t.posting_date = :end AND " + novel;
        return new Built(hits, evidence, params);
    }
}
