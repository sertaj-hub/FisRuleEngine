package com.fisre.engine.promotion;

import com.fisre.engine.db.Entity;
import java.util.List;

/**
 * A staging-row check: {@code rejectWhen} is a SQL predicate over the staged row aliased {@code s}.
 * Tokens: {mst} is the master schema; {stg.customer}, {stg.account}, {stg.txn} are this batch's staging partitions;
 * :posting is the expected posting date and :lb_start the first day of the duplicate look-back.
 * Row-local rules share one scan (CASE: first failing rule wins); rules that look at other tables run as their own
 * top-level queries so the planner can use joins (a correlated EXISTS inside CASE would run once per row).
 * Documented in specs/data-contract/validation-rules.md.
 */
public record ValidationRule(String id, Entity entity, String joins, String rejectWhen, String reason) {

    public ValidationRule(String id, Entity entity, String rejectWhen, String reason) {
        this(id, entity, "", rejectWhen, reason);
    }

    /** True if the predicate looks only at the staged row (no joins or sub-select), so it can share one scan with the others. */
    public boolean rowLocal() {
        return joins.isEmpty() && !rejectWhen.contains("SELECT");
    }

    private static String missing(String col) {
        return "(s." + col + " IS NULL OR TRIM(s." + col + ") = '')";
    }

    /**
     * Parent exists in master or in the same batch (if that batch row is itself bad, the batch fails anyway).
     * Written as LEFT JOINs on unique keys: an OR of correlated EXISTS would be planned as one sub-select per row.
     */
    private static ValidationRule unknownParent(String id, Entity entity, String col, String parentTable, String parentKey, String reason) {
        String joins = "LEFT JOIN {mst}." + parentTable + " pm ON pm." + parentKey + " = s." + col
                + " LEFT JOIN (SELECT DISTINCT " + parentKey + " FROM {stg." + parentTable + "}) ps ON ps." + parentKey + " = s." + col;
        return new ValidationRule(id, entity, joins, "(s." + col + " IS NULL OR (pm." + parentKey + " IS NULL AND ps." + parentKey + " IS NULL))", reason);
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
            unknownParent("ACC-003", Entity.ACCOUNT, "primary_customer_id", "customer", "customer_id", "primary_customer_id is missing or unknown"),
            new ValidationRule("ACC-004", Entity.ACCOUNT, "s.open_date IS NULL", "open_date is required"),
            new ValidationRule("ACC-005", Entity.ACCOUNT,
                    "(s.status IS NULL OR s.status NOT IN ('ACTIVE', 'DORMANT', 'FROZEN', 'CLOSED'))",
                    "status must be ACTIVE, DORMANT, FROZEN or CLOSED"),

            new ValidationRule("TXN-001", Entity.TXN, missing("transaction_id"), "transaction_id is required"),
            unknownParent("TXN-002", Entity.TXN, "account_id", "account", "account_id", "account_id is missing or unknown"),
            new ValidationRule("TXN-003", Entity.TXN,
                    "(s.txn_ts IS NULL OR s.posting_date IS NULL)", "txn_ts and posting_date are required"),
            new ValidationRule("TXN-004", Entity.TXN,
                    "(s.amount IS NULL OR s.amount <= 0)", "amount must be greater than zero"),
            new ValidationRule("TXN-005", Entity.TXN,
                    "(s.direction IS NULL OR s.direction NOT IN ('DEBIT', 'CREDIT'))",
                    "direction must be DEBIT or CREDIT"),
            new ValidationRule("TXN-006", Entity.TXN,
                    "(s.txn_type IS NULL OR s.txn_type NOT IN (SELECT r.txn_type FROM {mst}.ref_txn_type r))",
                    "txn_type is missing or not in ref_txn_type"),
            new ValidationRule("TXN-007", Entity.TXN, missing("currency"), "currency is required"),
            new ValidationRule("TXN-008", Entity.TXN,
                    "s.transaction_id IN (SELECT d.transaction_id FROM {stg.txn} d GROUP BY d.transaction_id HAVING COUNT(*) > 1)",
                    "transaction_id is duplicated within the batch"),
            new ValidationRule("TXN-009", Entity.TXN,
                    "EXISTS (SELECT 1 FROM {mst}.txn m WHERE m.transaction_id = s.transaction_id"
                            + " AND m.posting_date >= :lb_start AND m.posting_date < :posting)",
                    "transaction_id already exists in master within the look-back days"),
            new ValidationRule("TXN-010", Entity.TXN, "s.posting_date <> :posting",
                    "posting_date must be the batch business date minus the posting offset")
    );
}
