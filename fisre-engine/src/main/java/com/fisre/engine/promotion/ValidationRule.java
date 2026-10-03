package com.fisre.engine.promotion;

import com.fisre.engine.db.Entity;
import java.util.List;

/**
 * A staging-row check. {@code rejectWhen} is a SQL predicate over the stg row aliased {@code s}.
 * Tokens: {mst} and {stg} are schema names; :prior is the id of the live batch this batch will replace
 * ('' if none). Rules run in order per entity and the first match supplies the reject reason.
 * Documented in specs/data-contract/validation-rules.md.
 */
public record ValidationRule(String id, Entity entity, String rejectWhen, String reason) {

    private static String missing(String col) {
        return "(s." + col + " IS NULL OR TRIM(s." + col + ") = '')";
    }

    /** Parent exists in master or is a valid row of the same batch. */
    private static String unknownParent(String col, String parentTable, String parentKey) {
        return "(s." + col + " IS NULL OR (NOT EXISTS (SELECT 1 FROM {mst}." + parentTable + " p WHERE p." + parentKey + " = s." + col + ")"
                + " AND NOT EXISTS (SELECT 1 FROM {stg}." + parentTable + " p WHERE p.batch_id = s.batch_id"
                + " AND p.reject_reason IS NULL AND p." + parentKey + " = s." + col + ")))";
    }

    public static final List<ValidationRule> ALL = List.of(
            new ValidationRule("CUS-001", Entity.CUSTOMER, missing("customer_id"), "customer_id is required"),
            new ValidationRule("CUS-002", Entity.CUSTOMER,
                    "(s.customer_type IS NULL OR s.customer_type NOT IN ('INDIVIDUAL', 'ORGANIZATION'))",
                    "customer_type must be INDIVIDUAL or ORGANIZATION"),
            new ValidationRule("CUS-003", Entity.CUSTOMER, missing("full_name"), "full_name is required"),

            new ValidationRule("ACC-001", Entity.ACCOUNT, missing("account_id"), "account_id is required"),
            new ValidationRule("ACC-002", Entity.ACCOUNT,
                    "(s.product_type IS NULL OR s.product_type NOT IN ('CARD', 'LOAN', 'DEPOSIT'))",
                    "product_type must be CARD, LOAN or DEPOSIT"),
            new ValidationRule("ACC-003", Entity.ACCOUNT, unknownParent("primary_customer_id", "customer", "customer_id"),
                    "primary_customer_id is missing or unknown"),
            new ValidationRule("ACC-004", Entity.ACCOUNT, "s.open_date IS NULL", "open_date is required"),
            new ValidationRule("ACC-005", Entity.ACCOUNT,
                    "(s.status IS NULL OR s.status NOT IN ('ACTIVE', 'DORMANT', 'FROZEN', 'CLOSED'))",
                    "status must be ACTIVE, DORMANT, FROZEN or CLOSED"),

            new ValidationRule("TXN-001", Entity.TXN, missing("transaction_id"), "transaction_id is required"),
            new ValidationRule("TXN-002", Entity.TXN, unknownParent("account_id", "account", "account_id"),
                    "account_id is missing or unknown"),
            new ValidationRule("TXN-003", Entity.TXN,
                    "(s.txn_ts IS NULL OR s.posting_date IS NULL)", "txn_ts and posting_date are required"),
            new ValidationRule("TXN-004", Entity.TXN,
                    "(s.amount IS NULL OR s.amount <= 0)", "amount must be greater than zero"),
            new ValidationRule("TXN-005", Entity.TXN,
                    "(s.direction IS NULL OR s.direction NOT IN ('DEBIT', 'CREDIT'))",
                    "direction must be DEBIT or CREDIT"),
            new ValidationRule("TXN-006", Entity.TXN,
                    "(s.txn_type IS NULL OR NOT EXISTS (SELECT 1 FROM {mst}.ref_txn_type r WHERE r.txn_type = s.txn_type))",
                    "txn_type is missing or not in ref_txn_type"),
            new ValidationRule("TXN-007", Entity.TXN, missing("currency"), "currency is required"),
            new ValidationRule("TXN-008", Entity.TXN,
                    "EXISTS (SELECT 1 FROM {stg}.txn x WHERE x.batch_id = s.batch_id"
                            + " AND x.transaction_id = s.transaction_id AND x.stg_id <> s.stg_id)",
                    "transaction_id is duplicated within the batch"),
            new ValidationRule("TXN-009", Entity.TXN,
                    "EXISTS (SELECT 1 FROM {mst}.txn m WHERE m.transaction_id = s.transaction_id"
                            + " AND m.batch_id <> s.batch_id AND m.batch_id <> :prior)",
                    "transaction_id already exists in master from another batch")
    );
}
