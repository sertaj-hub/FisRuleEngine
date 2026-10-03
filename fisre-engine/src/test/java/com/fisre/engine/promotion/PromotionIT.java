package com.fisre.engine.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.spec.Req;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs against the database in FISRE_DB_* (default: local PostgreSQL). Same tests for every vendor. */
@SpringBootTest
class PromotionIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired PromotionService promotion;
    @Autowired FisreProperties props;

    String stg, mst, aml;

    @BeforeEach
    void clean() {
        stg = props.schemas().stg();
        mst = props.schemas().mst();
        aml = props.schemas().aml();
        for (String t : List.of(mst + ".txn", mst + ".account", mst + ".customer", stg + ".txn", stg + ".account",
                stg + ".customer", aml + ".batch_run_entity", aml + ".batch_run")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------

    void stgCustomer(String id, String type, String name) {
        jdbc.update("INSERT INTO " + stg + ".customer (load_id, customer_id, customer_type, full_name, country_code, status)"
                + " VALUES ('L1', ?, ?, ?, 'US', 'ACTIVE')", id, type, name);
    }

    void stgAccount(String id, String customerId, String product, String status) {
        jdbc.update("INSERT INTO " + stg + ".account (load_id, account_id, primary_customer_id, product_type, status, open_date, currency)"
                + " VALUES ('L1', ?, ?, ?, ?, ?, 'USD')", id, customerId, product, status, Date.valueOf("2020-01-15"));
    }

    void stgTxn(String id, String accountId, String type, String direction, String amount) {
        jdbc.update("INSERT INTO " + stg + ".txn (load_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                + " VALUES ('L1', ?, ?, ?, ?, ?, ?, ?, 'USD')", id, accountId, Timestamp.valueOf("2026-09-30 10:15:00"),
                Date.valueOf("2026-09-30"), type, direction, amount == null ? null : new BigDecimal(amount));
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    String status(String stgTable, String keyCol, String key) {
        return jdbc.queryForObject("SELECT rec_status FROM " + stg + "." + stgTable + " WHERE stg_id = (SELECT MAX(stg_id) FROM "
                + stg + "." + stgTable + " WHERE " + keyCol + " = ?)", String.class, key);
    }

    String reason(String stgTable, String keyCol, String key) {
        return jdbc.queryForObject("SELECT reject_reason FROM " + stg + "." + stgTable + " WHERE " + keyCol + " = ?", String.class, key);
    }

    void loadHappyPath() {
        stgCustomer("C1", "INDIVIDUAL", "Ada Lovelace");
        stgAccount("A1", "C1", "DEPOSIT", "ACTIVE");
        stgTxn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "9500.00");
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    @Req({"REQ-STG-001", "REQ-DB-001"})
    void migrationsCreateTablesAndStagingRowsDefaultToNew() {
        stgCustomer("C1", "INDIVIDUAL", "Ada Lovelace");
        assertThat(status("customer", "customer_id", "C1")).isEqualTo("NEW");
        assertThat(count(mst + ".ref_txn_type")).isGreaterThanOrEqualTo(20);
    }

    @Test
    @Req("REQ-PRM-001")
    void validRowsArePromotedParentsFirst() {
        loadHappyPath();

        List<PromotionService.EntityResult> results = promotion.promote();

        assertThat(results).extracting(PromotionService.EntityResult::entity).containsExactly("CUSTOMER", "ACCOUNT", "TXN");
        assertThat(count(mst + ".customer")).isEqualTo(1);
        assertThat(count(mst + ".account")).isEqualTo(1);
        assertThat(count(mst + ".txn")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM " + mst + ".txn WHERE transaction_id = 'T1'", BigDecimal.class))
                .isEqualByComparingTo("9500.00");
        assertThat(status("txn", "transaction_id", "T1")).isEqualTo("PROCESSED");
    }

    @Test
    @Req("REQ-PRM-002")
    void invalidRowsAreRejectedWithRuleIdAndNeverPromoted() {
        stgCustomer("C1", "INDIVIDUAL", "Ada");
        stgCustomer("C2", "ALIEN", "Bad Type");
        stgAccount("A1", "C1", "DEPOSIT", "ACTIVE");
        stgTxn("T1", "A1", "NOT_A_TYPE", "CREDIT", "10");
        stgTxn("T2", "A1", "CASH_DEPOSIT", "CREDIT", "-5");
        stgTxn("T3", "A1", "CASH_DEPOSIT", "SIDE", "5");

        promotion.promote();

        assertThat(count(mst + ".customer")).isEqualTo(1);
        assertThat(count(mst + ".txn")).isZero();
        assertThat(status("customer", "customer_id", "C2")).isEqualTo("REJECTED");
        assertThat(reason("customer", "customer_id", "C2")).startsWith("CUS-002:");
        assertThat(reason("txn", "transaction_id", "T1")).startsWith("TXN-006:");
        assertThat(reason("txn", "transaction_id", "T2")).startsWith("TXN-004:");
        assertThat(reason("txn", "transaction_id", "T3")).startsWith("TXN-005:");
    }

    @Test
    @Req("REQ-PRM-003")
    void latestRowWinsWithinBatch_andFirstDeliveredTransactionWins() {
        stgCustomer("C1", "INDIVIDUAL", "Old Name");
        stgCustomer("C1", "INDIVIDUAL", "New Name");
        stgAccount("A1", "C1", "DEPOSIT", "ACTIVE");
        stgTxn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "100");
        promotion.promote();
        assertThat(jdbc.queryForObject("SELECT full_name FROM " + mst + ".customer WHERE customer_id = 'C1'", String.class)).isEqualTo("New Name");

        stgTxn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "999"); // redelivered with a different amount
        promotion.promote();

        assertThat(count(mst + ".txn")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM " + mst + ".txn WHERE transaction_id = 'T1'", BigDecimal.class)).isEqualByComparingTo("100");
    }

    @Test
    @Req("REQ-PRM-004")
    void existingCustomerAndAccountAreUpdated() {
        loadHappyPath();
        promotion.promote();

        stgCustomer("C1", "INDIVIDUAL", "Ada King");
        stgAccount("A1", "C1", "DEPOSIT", "DORMANT");
        promotion.promote();

        assertThat(count(mst + ".customer")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT full_name FROM " + mst + ".customer WHERE customer_id = 'C1'", String.class)).isEqualTo("Ada King");
        assertThat(jdbc.queryForObject("SELECT status FROM " + mst + ".account WHERE account_id = 'A1'", String.class)).isEqualTo("DORMANT");
    }

    @Test
    @Req("REQ-PRM-005")
    void rerunWithNothingNewChangesNothing() {
        loadHappyPath();
        promotion.promote();

        List<PromotionService.EntityResult> second = promotion.promote();

        assertThat(second).allSatisfy(r -> {
            assertThat(r.promoted()).isZero();
            assertThat(r.rejected()).isZero();
        });
        assertThat(count(mst + ".txn")).isEqualTo(1);
    }

    @Test
    @Req("REQ-PRM-006")
    void childOfRejectedOrUnknownParentIsRejected() {
        stgCustomer("C9", "ALIEN", "Rejected Parent");
        stgAccount("A9", "C9", "CARD", "ACTIVE");
        stgTxn("T9", "A9", "POS_PURCHASE", "DEBIT", "20");
        stgTxn("T8", "NO_SUCH_ACCOUNT", "POS_PURCHASE", "DEBIT", "20");

        promotion.promote();

        assertThat(reason("account", "account_id", "A9")).startsWith("ACC-003:");
        assertThat(reason("txn", "transaction_id", "T9")).startsWith("TXN-002:");
        assertThat(reason("txn", "transaction_id", "T8")).startsWith("TXN-002:");
        assertThat(count(mst + ".account")).isZero();
    }

    @Test
    @Req("REQ-RUN-001")
    void everyRunIsRecordedWithPerEntityCounts() {
        loadHappyPath();
        stgTxn("T2", "A1", "CASH_DEPOSIT", "CREDIT", "0");

        promotion.promote();

        assertThat(jdbc.queryForObject("SELECT status FROM " + aml + ".batch_run", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT promoted_cnt FROM " + aml + ".batch_run_entity WHERE entity = 'TXN'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rejected_cnt FROM " + aml + ".batch_run_entity WHERE entity = 'TXN'", Long.class)).isEqualTo(1);
    }
}
