package com.fisre.engine.db;

import java.util.List;

/** Describes a stg/mst table pair: its key and the business columns that are copied between them. */
public record Entity(String name, String table, String keyColumn, List<String> columns) {

    public static final Entity CUSTOMER = new Entity("CUSTOMER", "customer", "customer_id", List.of(
            "customer_id", "customer_type", "full_name", "birth_or_formation_dt",
            "country_code", "state_code", "customer_since", "status"));

    public static final Entity ACCOUNT = new Entity("ACCOUNT", "account", "account_id", List.of(
            "account_id", "primary_customer_id", "product_type", "product_subtype", "status",
            "open_date", "close_date", "currency", "branch_code", "credit_limit"));

    public static final Entity TXN = new Entity("TXN", "txn", "transaction_id", List.of(
            "transaction_id", "account_id", "txn_ts", "posting_date", "txn_type", "direction",
            "amount", "currency", "channel", "counterparty_name", "counterparty_account",
            "counterparty_country", "merchant_category_code", "description"));

    /** Promotion order: parents before children. */
    public static final List<Entity> PROMOTION_ORDER = List.of(CUSTOMER, ACCOUNT, TXN);

    /** Columns excluding the key, i.e. the ones that change on update. */
    public List<String> nonKeyColumns() {
        return columns.stream().filter(c -> !c.equals(keyColumn)).toList();
    }
}
