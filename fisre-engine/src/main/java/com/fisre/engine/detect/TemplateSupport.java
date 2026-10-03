package com.fisre.engine.detect;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared config parsing for the templates. */
abstract class TemplateSupport implements Template {

    private static final Set<String> PRODUCTS = Set.of("CARD", "LOAN", "DEPOSIT");

    protected abstract Set<String> allowedKeys();

    protected abstract void validateTemplate(JsonNode c);

    @Override
    public final void validate(JsonNode c) {
        if (c == null || !c.isObject()) {
            throw new IllegalArgumentException("config must be an object");
        }
        c.fieldNames().forEachRemaining(k -> {
            if (!allowedKeys().contains(k) && !k.equals("product_types")) {
                throw new IllegalArgumentException("unknown config key '" + k + "' for template " + code());
            }
        });
        products(c);
        validateTemplate(c);
    }

    protected static List<String> products(JsonNode c) {
        List<String> p = Filter.strings(c.get("product_types"), "product_types");
        for (String x : p) {
            if (!PRODUCTS.contains(x)) {
                throw new IllegalArgumentException("product_types contains unknown product '" + x + "'");
            }
        }
        return p;
    }

    /** Predicate on account alias {@code a}. */
    protected static String productClause(JsonNode c, Map<String, Object> params) {
        List<String> p = products(c);
        if (p.isEmpty()) {
            return "1 = 1";
        }
        params.put("products", p);
        return "a.product_type IN (:products)";
    }

    protected static int posInt(JsonNode c, String key) {
        JsonNode n = c.get(key);
        if (n == null || !n.isInt() || n.asInt() < 1) {
            throw new IllegalArgumentException("'" + key + "' is required and must be a whole number of at least 1");
        }
        return n.asInt();
    }

    protected static int optInt(JsonNode c, String key, int dflt) {
        return c.has(key) ? posInt(c, key) : dflt;
    }

    protected static BigDecimal decimal(JsonNode c, String key, BigDecimal dflt) {
        JsonNode n = c.get(key);
        if (n == null) {
            if (dflt == null) {
                throw new IllegalArgumentException("'" + key + "' is required");
            }
            return dflt;
        }
        if (!n.isNumber() || n.decimalValue().signum() < 0) {
            throw new IllegalArgumentException("'" + key + "' must be a number of zero or more");
        }
        return n.decimalValue();
    }

    protected static Filter filter(JsonNode c, String key, boolean required) {
        if (required && !c.has(key)) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        return Filter.parse(c.get(key), key);
    }

    protected static java.sql.Date date(LocalDate d) {
        return java.sql.Date.valueOf(d);
    }
}
