package com.fisre.engine.promotion;

import com.fisre.engine.db.Entity;

/**
 * A staging-row check. {@code rejectWhen} is a SQL predicate over the stg row aliased {@code s};
 * {@code {mst}} is replaced with the master schema name. Rules are applied in order and the
 * first one that matches supplies the reject reason. Documented in specs/data-contract/validation-rules.md.
 */
public record ValidationRule(String id, Entity entity, String rejectWhen, String reason) {

    private static final String MISSING = "(%s IS NULL OR TRIM(%s) = '')";

    private static String missing(String col) {
        return MISSING.formatted("s." + col, "s." + col);
    }

    public static final java.util.List<ValidationRule> ALL = java.util.List.of(
            new ValidationRule("CUS-001", Entity.CUSTOMER, missing("customer_id"), "customer_id is required"),
            new ValidationRule("CUS-002", Entity.CUSTOMER,
                    "(s.customer_type IS NULL OR s.customer_type NOT IN ('INDIVIDUAL', 'ORGANIZATION'))",
                    "customer_type must be INDIVIDUAL or ORGANIZATION"),
            new ValidationRule("CUS-003", Entity.CUSTOMER, missing("full_name"), "full_name is required"),

            new ValidationRule("ACC-001", Entity.ACCOUNT, missing("account_id"), "account_id is required"),
            new ValidationRule("ACC-002", Entity.ACCOUNT,
                    "(s.product_type IS NULL OR s.product_type NOT IN ('CARD', 'LOAN', 'DEPOSIT'))",
                    "product_type must be CARD, LOAN or DEPOSIT"),
            new ValidationRule("ACC-003", Entity.ACCOUNT,
                    "(s.primary_customer_id IS NULL OR NOT EXISTS "
                            + "(SELECT 1 FROM {mst}.customer c WHERE c.customer_id = s.primary_customer_id))",
                    "primary_customer_id is missing or unknown"),
            new ValidationRule("ACC-004", Entity.ACCOUNT, "s.open_date IS NULL", "open_date is required"),
            new ValidationRule("ACC-005", Entity.ACCOUNT,
                    "(s.status IS NULL OR s.status NOT IN ('ACTIVE', 'DORMANT', 'FROZEN', 'CLOSED'))",
                    "status must be ACTIVE, DORMANT, FROZEN or CLOSED"),

            new ValidationRule("TXN-001", Entity.TXN, missing("transaction_id"), "transaction_id is required"),
            new ValidationRule("TXN-002", Entity.TXN,
                    "(s.account_id IS NULL OR NOT EXISTS "
                            + "(SELECT 1 FROM {mst}.account a WHERE a.account_id = s.account_id))",
                    "account_id is missing or unknown"),
            new ValidationRule("TXN-003", Entity.TXN,
                    "(s.txn_ts IS NULL OR s.posting_date IS NULL)", "txn_ts and posting_date are required"),
            new ValidationRule("TXN-004", Entity.TXN,
                    "(s.amount IS NULL OR s.amount <= 0)", "amount must be greater than zero"),
            new ValidationRule("TXN-005", Entity.TXN,
                    "(s.direction IS NULL OR s.direction NOT IN ('DEBIT', 'CREDIT'))",
                    "direction must be DEBIT or CREDIT"),
            new ValidationRule("TXN-006", Entity.TXN,
                    "(s.txn_type IS NULL OR NOT EXISTS "
                            + "(SELECT 1 FROM {mst}.ref_txn_type r WHERE r.txn_type = s.txn_type))",
                    "txn_type is missing or not in ref_txn_type"),
            new ValidationRule("TXN-007", Entity.TXN, missing("currency"), "currency is required")
    );
}
