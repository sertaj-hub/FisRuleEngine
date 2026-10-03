package com.fisre.engine.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs against the local PostgreSQL configured by FISRE_DB_* (see README). */
@SpringBootTest
class BatchIT {

    static final String D1 = "2026-09-30";
    static final String D2 = "2026-10-01";

    @Autowired JdbcTemplate jdbc;
    @Autowired BatchService service;
    @Autowired FisreProperties props;

    String stg, mst, aml;

    @BeforeEach
    void clean() {
        stg = props.schemas().stg();
        mst = props.schemas().mst();
        aml = props.schemas().aml();
        jdbc.execute("DROP TRIGGER IF EXISTS fail_txn ON " + mst + ".txn");
        for (String t : List.of(mst + ".txn", mst + ".account", mst + ".customer", stg + ".txn", stg + ".account",
                stg + ".customer", aml + ".load_batch_entity", aml + ".load_batch")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------

    void batch(String id, String date, String status) {
        jdbc.update("INSERT INTO " + aml + ".load_batch (batch_id, business_date, status) VALUES (?, ?, ?)",
                id, Date.valueOf(date), status);
    }

    void customer(String b, String id, String type, String name) {
        jdbc.update("INSERT INTO " + stg + ".customer (batch_id, customer_id, customer_type, full_name, country_code, status)"
                + " VALUES (?, ?, ?, ?, 'US', 'ACTIVE')", b, id, type, name);
    }

    void account(String b, String id, String customerId, String product, String status) {
        jdbc.update("INSERT INTO " + stg + ".account (batch_id, account_id, primary_customer_id, product_type, status, open_date, currency)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'USD')", b, id, customerId, product, status, Date.valueOf("2020-01-15"));
    }

    void txn(String b, String id, String accountId, String type, String direction, String amount) {
        jdbc.update("INSERT INTO " + stg + ".txn (batch_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USD')", b, id, accountId, Timestamp.valueOf("2026-09-30 10:15:00"),
                Date.valueOf("2026-09-30"), type, direction, new BigDecimal(amount));
    }

    /** A valid batch: one customer, one account, the given transactions (id to amount). */
    void validBatch(String id, String date, String... txnIdAmount) {
        batch(id, date, "LOADED");
        customer(id, "C1", "INDIVIDUAL", "Ada Lovelace");
        account(id, "A1", "C1", "DEPOSIT", "ACTIVE");
        for (int i = 0; i < txnIdAmount.length; i += 2) {
            txn(id, txnIdAmount[i], "A1", "CASH_DEPOSIT", "CREDIT", txnIdAmount[i + 1]);
        }
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    String batchStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM " + aml + ".load_batch WHERE batch_id = ?", String.class, id);
    }

    String reason(String table, String keyCol, String key) {
        return jdbc.queryForObject("SELECT reject_reason FROM " + stg + "." + table + " WHERE " + keyCol + " = ?", String.class, key);
    }

    BigDecimal amount(String txnId) {
        return jdbc.queryForObject("SELECT amount FROM " + mst + ".txn WHERE transaction_id = ?", BigDecimal.class, txnId);
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    @Req("REQ-CFG-001")
    void tablesLiveInTheSchemasNamedByConfiguration() {
        for (String[] t : new String[][] {{stg, "txn"}, {mst, "txn"}, {aml, "load_batch"}}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Long.class, t[0], t[1])).as(t[0] + "." + t[1]).isEqualTo(1);
        }
    }

    @Test
    @Req({"REQ-STG-001", "REQ-DB-001"})
    void stagingRowsNeedARegisteredBatch_andMigrationsSeedReferenceData() {
        assertThatThrownBy(() -> customer("NO_SUCH_BATCH", "C1", "INDIVIDUAL", "x")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(mst + ".ref_txn_type")).isGreaterThanOrEqualTo(20);
    }

    @Test
    @Req({"REQ-PRM-001", "REQ-BAT-004"})
    void cleanBatchIsPromotedThenStagingIsDeleted() {
        validBatch("B1", D1, "T1", "9500.00", "T2", "100");

        BatchService.Result r = service.promote("B1");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(count(mst + ".customer")).isEqualTo(1);
        assertThat(count(mst + ".account")).isEqualTo(1);
        assertThat(count(mst + ".txn")).isEqualTo(2);
        assertThat(amount("T1")).isEqualByComparingTo("9500.00");
        assertThat(jdbc.queryForObject("SELECT batch_id FROM " + mst + ".txn WHERE transaction_id = 'T1'", String.class)).isEqualTo("B1");
        assertThat(count(stg + ".txn") + count(stg + ".account") + count(stg + ".customer")).isZero();
        assertThat(batchStatus("B1")).isEqualTo("CLEANED");
        assertThat(jdbc.queryForObject("SELECT promoted_cnt FROM " + aml + ".load_batch_entity WHERE batch_id = 'B1' AND entity = 'TXN'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT staged_cnt FROM " + aml + ".load_batch_entity WHERE batch_id = 'B1' AND entity = 'TXN'", Long.class)).isEqualTo(2);
    }

    @Test
    @Req("REQ-BAT-001")
    void onlyALoadedBatchCanBePromoted() {
        validBatch("B1", D1, "T1", "10");
        batch("B2", D2, "LOADING");

        service.promote("B1");

        assertThatThrownBy(() -> service.promote("B1")).hasMessageContaining("expected LOADED");
        assertThatThrownBy(() -> service.promote("B2")).hasMessageContaining("is in status LOADING");
        assertThatThrownBy(() -> service.promote("NOPE")).hasMessageContaining("Unknown batch_id");
    }

    @Test
    @Req({"REQ-BAT-002", "REQ-PRM-002", "REQ-PRM-006"})
    void anyRejectFailsTheWholeBatch_andNothingReachesMaster() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "INDIVIDUAL", "Ada");
        customer("B1", "C2", "ALIEN", "Bad Type");
        account("B1", "A1", "C1", "DEPOSIT", "ACTIVE");
        account("B1", "A2", "C2", "CARD", "ACTIVE");               // parent rejected
        txn("B1", "T1", "A1", "CASH_DEPOSIT", "CREDIT", "10");     // fine
        txn("B1", "T2", "A1", "NOT_A_TYPE", "CREDIT", "10");
        txn("B1", "T3", "A1", "CASH_DEPOSIT", "CREDIT", "-5");
        txn("B1", "T4", "A2", "POS_PURCHASE", "DEBIT", "20");      // grandparent chain rejected
        txn("B1", "T5", "GHOST", "POS_PURCHASE", "DEBIT", "20");

        BatchService.Result r = service.promote("B1");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(batchStatus("B1")).isEqualTo("FAILED");
        assertThat(count(mst + ".customer") + count(mst + ".account") + count(mst + ".txn")).isZero();
        assertThat(reason("customer", "customer_id", "C2")).startsWith("CUS-002:");
        assertThat(reason("account", "account_id", "A2")).startsWith("ACC-003:");
        assertThat(reason("txn", "transaction_id", "T2")).startsWith("TXN-006:");
        assertThat(reason("txn", "transaction_id", "T3")).startsWith("TXN-004:");
        assertThat(reason("txn", "transaction_id", "T4")).startsWith("TXN-002:");
        assertThat(reason("txn", "transaction_id", "T5")).startsWith("TXN-002:");
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM " + stg + ".txn WHERE transaction_id = 'T1'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT rejected_cnt FROM " + aml + ".load_batch_entity WHERE batch_id = 'B1' AND entity = 'TXN'", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT error_msg FROM " + aml + ".load_batch WHERE batch_id = 'B1'", String.class)).contains("failed validation");
    }

    @Test
    @Req("REQ-BAT-003")
    void failureDuringLoadLeavesMasterUnchanged() {
        validBatch("B1", D1, "T1", "10");
        jdbc.execute("CREATE OR REPLACE FUNCTION " + mst + ".boom() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'simulated failure'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_txn BEFORE INSERT ON " + mst + ".txn FOR EACH ROW EXECUTE FUNCTION " + mst + ".boom()");

        assertThatThrownBy(() -> service.promote("B1")).hasMessageContaining("simulated failure");

        jdbc.execute("DROP TRIGGER fail_txn ON " + mst + ".txn");
        assertThat(count(mst + ".customer") + count(mst + ".account") + count(mst + ".txn")).isZero();
        assertThat(batchStatus("B1")).isEqualTo("FAILED");
        assertThat(count(stg + ".txn")).isEqualTo(1);   // staged data untouched
    }

    @Test
    @Req("REQ-BAT-005")
    void cleanDeletesAFailedBatchesStagingRows() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "ALIEN", "Bad");
        service.promote("B1");

        service.clean("B1");

        assertThat(count(stg + ".customer")).isZero();
        assertThat(batchStatus("B1")).isEqualTo("FAILED");
        assertThatCode(() -> service.clean("B1")).doesNotThrowAnyException();   // idempotent
        batch("B2", D1, "LOADED");   // reload under a new id
        assertThatThrownBy(() -> service.clean("B2")).hasMessageContaining("needs FAILED or PROMOTED");
    }

    @Test
    @Req("REQ-BAT-006")
    void laterBatchForSameDateReplacesTheEarlierOnesTransactions() {
        validBatch("B1", D1, "T1", "100", "T2", "200");
        service.promote("B1");

        validBatch("B2", D1, "T1", "150", "T3", "300");   // corrected delivery of the same business date
        BatchService.Result r = service.promote("B2");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(count(mst + ".txn")).isEqualTo(2);
        assertThat(amount("T1")).isEqualByComparingTo("150");
        assertThat(amount("T3")).isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + mst + ".txn WHERE transaction_id = 'T2'", Long.class)).isZero();
        assertThat(batchStatus("B1")).isEqualTo("SUPERSEDED");
        assertThat(batchStatus("B2")).isEqualTo("CLEANED");
        assertThat(jdbc.queryForObject("SELECT superseded_by FROM " + aml + ".load_batch WHERE batch_id = 'B1'", String.class)).isEqualTo("B2");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + aml + ".load_batch SET status = 'PROMOTED' WHERE batch_id = 'B1'"))
                .as("two live batches for one date must be impossible").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Req("REQ-BAT-007")
    void duplicateTransactionIdsFailTheBatch() {
        validBatch("B1", D1, "T1", "10");
        service.promote("B1");

        validBatch("B2", D2, "T1", "10", "T9", "5", "T9", "6");   // T1 live in B1 (other date); T9 twice in B2
        BatchService.Result r = service.promote("B2");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(reason("txn", "transaction_id", "T1")).startsWith("TXN-009:");
        assertThat(jdbc.queryForList("SELECT reject_reason FROM " + stg + ".txn WHERE transaction_id = 'T9'", String.class))
                .hasSize(2).allSatisfy(s -> assertThat(s).startsWith("TXN-008:"));
    }

    @Test
    @Req({"REQ-PRM-003", "REQ-PRM-004"})
    void latestRowWinsWithinBatch_andExistingMasterRowsAreUpdated() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "INDIVIDUAL", "Old Name");
        customer("B1", "C1", "INDIVIDUAL", "New Name");
        account("B1", "A1", "C1", "DEPOSIT", "ACTIVE");
        txn("B1", "T1", "A1", "CASH_DEPOSIT", "CREDIT", "1");
        service.promote("B1");
        assertThat(jdbc.queryForObject("SELECT full_name FROM " + mst + ".customer WHERE customer_id = 'C1'", String.class)).isEqualTo("New Name");

        batch("B2", D2, "LOADED");
        customer("B2", "C1", "INDIVIDUAL", "Ada King");
        account("B2", "A1", "C1", "DEPOSIT", "DORMANT");
        txn("B2", "T2", "A1", "CASH_DEPOSIT", "CREDIT", "2");
        service.promote("B2");

        assertThat(count(mst + ".customer")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT full_name FROM " + mst + ".customer WHERE customer_id = 'C1'", String.class)).isEqualTo("Ada King");
        assertThat(jdbc.queryForObject("SELECT status FROM " + mst + ".account WHERE account_id = 'A1'", String.class)).isEqualTo("DORMANT");
        assertThat(jdbc.queryForObject("SELECT batch_id FROM " + mst + ".customer WHERE customer_id = 'C1'", String.class)).isEqualTo("B2");
    }

    @Test
    @Req("REQ-BAT-008")
    void reopenLetsCorrectedStagingRowsBePromoted() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "ALIEN", "Ada");
        assertThat(service.promote("B1").outcome()).isEqualTo(BatchService.Outcome.FAILED);

        jdbc.update("UPDATE " + stg + ".customer SET customer_type = 'INDIVIDUAL' WHERE customer_id = 'C1'");   // ETL fixes the row
        service.reopen("B1");
        assertThat(batchStatus("B1")).isEqualTo("LOADED");
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM " + stg + ".customer WHERE customer_id = 'C1'", String.class)).isNull();

        assertThat(service.promote("B1").outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(count(mst + ".customer")).isEqualTo(1);
    }

    @Test
    @Req("REQ-BAT-009")
    void anEmptyBatchFailsAndNeverReplacesAGoodOne() {
        validBatch("B1", D1, "T1", "10");
        service.promote("B1");
        batch("B2", D1, "LOADED");   // same date, nothing staged

        BatchService.Result r = service.promote("B2");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(r.message()).contains("empty");
        assertThat(batchStatus("B1")).isEqualTo("CLEANED");
        assertThat(count(mst + ".txn")).isEqualTo(1);
    }
}
