package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A transaction filter from rule config; turns into a SQL predicate with bound parameters only. */
public record Filter(List<String> txnTypes, Boolean cash, String direction, List<String> channels,
                     BigDecimal minAmount, BigDecimal maxAmount) {

    private static final Set<String> KEYS = Set.of("txn_types", "cash", "direction", "channels", "min_amount", "max_amount");

    public static Filter parse(JsonNode n, String where) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return new Filter(List.of(), null, "ANY", List.of(), null, null);
        }
        if (!n.isObject()) {
            throw new IllegalArgumentException(where + " must be an object");
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!KEYS.contains(k)) {
                throw new IllegalArgumentException("unknown key '" + k + "' in " + where);
            }
        });
        String dir = n.has("direction") ? n.get("direction").asText() : "ANY";
        if (!Set.of("CREDIT", "DEBIT", "ANY").contains(dir)) {
            throw new IllegalArgumentException(where + ".direction must be CREDIT, DEBIT or ANY");
        }
        if (n.has("cash") && !n.get("cash").isBoolean()) {
            throw new IllegalArgumentException(where + ".cash must be true or false");
        }
        return new Filter(strings(n.get("txn_types"), where + ".txn_types"), n.has("cash") ? n.get("cash").asBoolean() : null, dir,
                strings(n.get("channels"), where + ".channels"), number(n, "min_amount", where), number(n, "max_amount", where));
    }

    static List<String> strings(JsonNode n, String where) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull()) {
            return out;
        }
        if (!n.isArray() || n.isEmpty()) {
            throw new IllegalArgumentException(where + " must be a non-empty list");
        }
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static BigDecimal number(JsonNode n, String key, String where) {
        if (!n.has(key)) {
            return null;
        }
        if (!n.get(key).isNumber()) {
            throw new IllegalArgumentException(where + "." + key + " must be a number");
        }
        return n.get(key).decimalValue();
    }

    /** SQL predicate over transaction alias {@code t}; values go into {@code params} under {@code prefix}. */
    public String sql(String t, String prefix, Map<String, Object> params, String mst) {
        List<String> p = new ArrayList<>();
        if (!txnTypes.isEmpty()) {
            p.add(t + ".txn_type IN (:" + prefix + "types)");
            params.put(prefix + "types", txnTypes);
        }
        if (cash != null) {
            p.add(t + ".txn_type " + (cash ? "IN" : "NOT IN") + " (SELECT r.txn_type FROM " + mst + ".ref_txn_type r WHERE r.is_cash = 'Y')");
        }
        if (!"ANY".equals(direction)) {
            p.add(t + ".direction = :" + prefix + "dir");
            params.put(prefix + "dir", direction);
        }
        if (!channels.isEmpty()) {
            p.add(t + ".channel IN (:" + prefix + "channels)");
            params.put(prefix + "channels", channels);
        }
        if (minAmount != null) {
            p.add(t + ".amount >= :" + prefix + "min");
            params.put(prefix + "min", minAmount);
        }
        if (maxAmount != null) {
            p.add(t + ".amount < :" + prefix + "max");
            params.put(prefix + "max", maxAmount);
        }
        return p.isEmpty() ? "1 = 1" : String.join(" AND ", p);
    }
}
