package com.fisre.engine.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.spec.Req;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against the local PostgreSQL configured by FISRE_DB_* (see README).
 * Business date D1 = 2026-10-01, so its transactions are posted on 2026-09-30 (posting offset 1).
 */
@SpringBootTest
class BatchIT {

    static final String D1 = "2026-10-01";
    static final String D2 = "2026-10-02";

    @Autowired JdbcTemplate jdbc;
    @Autowired BatchService service;
    @Autowired FisreProperties props;

    String stg, mst, aml;
    Fixtures fx;
    final Map<String, String> businessDate = new HashMap<>();

    @BeforeEach
    void clean() {
        stg = props.schemas().stg();
        mst = props.schemas().mst();
        aml = props.schemas().aml();
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
        businessDate.clear();
    }

    // ---- fixtures -------------------------------------------------------------------------------

    void batch(String id, String date, String status) {
        jdbc.update("INSERT INTO " + aml + ".load_batch (batch_id, business_date, status) VALUES (?, ?, ?)", id, Date.valueOf(date), status);
        businessDate.put(id, date);
    }

    String postingOf(String batchId) {
        return LocalDate.parse(businessDate.get(batchId)).minusDays(1).toString();
    }

    void customer(String b, String id, String type, String name) {
        jdbc.update("INSERT INTO " + stg + ".customer (batch_id, customer_id, customer_type, full_name, country_code, status)"
                + " VALUES (?, ?, ?, ?, 'US', 'ACTIVE')", b, id, type, name);
    }

    void account(String b, String id, String customerId, String product, String status) {
        jdbc.update("INSERT INTO " + stg + ".account (batch_id, account_id, primary_customer_id, product_type, status, open_date, currency)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'USD')", b, id, customerId, product, status, Date.valueOf("2020-01-15"));
    }

    void txnOn(String b, String posting, String id, String accountId, String type, String direction, String amount) {
        jdbc.update("INSERT INTO " + stg + ".txn (batch_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USD')", b, id, accountId, Timestamp.valueOf(posting + " 10:15:00"),
                Date.valueOf(posting), type, direction, new BigDecimal(amount));
    }

    void txn(String b, String id, String accountId, String type, String direction, String amount) {
        txnOn(b, postingOf(b), id, accountId, type, direction, amount);
    }

    /** A valid LOADED batch: customer C1, account A1, and the given transactions (id, amount, ...). */
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

    long seq(String id) {
        return jdbc.queryForObject("SELECT batch_seq FROM " + aml + ".load_batch WHERE batch_id = ?", Long.class, id);
    }

    long rejects(String batchId, String entity, String ruleId) {
        List<Long> n = jdbc.queryForList("SELECT reject_count FROM " + aml + ".load_reject WHERE batch_id = ? AND entity = ? AND rule_id = ?",
                Long.class, batchId, entity, ruleId);
        return n.isEmpty() ? 0 : n.get(0);
    }

    BigDecimal amount(String txnId) {
        return jdbc.queryForObject("SELECT amount FROM " + mst + ".txn WHERE transaction_id = ?", BigDecimal.class, txnId);
    }

    boolean stagingExists(String batchId) {
        long s = seq(batchId);
        return fx.exists(stg + ".txn_b" + s) || fx.exists(stg + ".customer_b" + s) || fx.exists(stg + ".account_b" + s);
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
    void registeringABatchCreatesItsStagingPartitions_andOtherBatchIdsAreRefused() {
        batch("B1", D1, "LOADING");
        long s = seq("B1");
        assertThat(fx.partitions(stg, "txn")).containsExactly("txn_b" + s);
        assertThat(fx.partitions(stg, "customer")).containsExactly("customer_b" + s);
        assertThat(fx.partitions(stg, "account")).containsExactly("account_b" + s);
        assertThat(jdbc.queryForObject("SELECT relpersistence FROM pg_class WHERE oid = to_regclass(?)", String.class, stg + ".txn_b" + s))
                .as("unlogged: no write-ahead log for staging").isEqualTo("u");
        assertThatThrownBy(() -> customer("NO_SUCH_BATCH", "C1", "INDIVIDUAL", "x")).isInstanceOf(DataAccessException.class);
        assertThat(count(mst + ".ref_txn_type")).isGreaterThanOrEqualTo(20);
    }

    @Test
    @Req({"REQ-PRM-001", "REQ-BAT-004"})
    void cleanBatchIsPromotedThenItsStagingPartitionsAreDropped() {
        validBatch("B1", D1, "T1", "9500.00", "T2", "100");
        long s = seq("B1");

        BatchService.Result r = service.promote("B1");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(count(mst + ".customer")).isEqualTo(1);
        assertThat(count(mst + ".account")).isEqualTo(1);
        assertThat(count(mst + ".txn")).isEqualTo(2);
        assertThat(amount("T1")).isEqualByComparingTo("9500.00");
        assertThat(jdbc.queryForObject("SELECT batch_id FROM " + mst + ".txn WHERE transaction_id = 'T1'", String.class)).isEqualTo("B1");
        assertThat(fx.exists(stg + ".txn_b" + s)).isFalse();
        assertThat(fx.partitions(stg, "txn")).isEmpty();
        assertThat(batchStatus("B1")).isEqualTo("CLEANED");
        assertThat(jdbc.queryForObject("SELECT promoted_cnt FROM " + aml + ".load_batch_entity WHERE batch_id = 'B1' AND entity = 'TXN'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT staged_cnt FROM " + aml + ".load_batch_entity WHERE batch_id = 'B1' AND entity = 'TXN'", Long.class)).isEqualTo(2);
    }

    @Test
    @Req("REQ-PRM-008")
    void masterTransactionsLiveInOneAttachedPartitionPerPostingDay_withPrimaryKeyAndAccountIndex() {
        validBatch("B1", D1, "T1", "10");

        service.promote("B1");

        assertThat(fx.partitions(mst, "txn")).containsExactly("txn_20260930");
        List<String> defs = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = 'txn_20260930'", String.class, mst);
        assertThat(defs).anyMatch(d -> d.contains("UNIQUE") && d.contains("(transaction_id, posting_date)"));
        assertThat(defs).anyMatch(d -> d.contains("(account_id, posting_date)"));
        assertThat(jdbc.queryForObject("SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c WHERE c.oid = to_regclass(?)", String.class, mst + ".txn_20260930"))
                .contains("2026-09-30").contains("2026-10-01");
        assertThat(fx.partitions(mst, "txn").stream().filter(p -> p.startsWith("txn_new_"))).isEmpty();
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
    void anyRejectFailsTheWholeBatch_withCountsAndSamplesRecorded_andStagingUntouched() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "INDIVIDUAL", "Ada");
        customer("B1", "C2", "ALIEN", "Bad Type");
        account("B1", "A1", "C1", "DEPOSIT", "ACTIVE");
        account("B1", "A2", "NOCUST", "CARD", "ACTIVE");                  // parent in neither master nor this batch
        txn("B1", "T1", "A1", "CASH_DEPOSIT", "CREDIT", "10");            // fine
        txn("B1", "T2", "A1", "NOT_A_TYPE", "CREDIT", "10");
        txn("B1", "T3", "A1", "CASH_DEPOSIT", "CREDIT", "-5");
        txn("B1", "T5", "GHOST", "POS_PURCHASE", "DEBIT", "20");
        txn("B1", "T6", "A1", "CASH_DEPOSIT", "SIDE", "20");

        BatchService.Result r = service.promote("B1");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(batchStatus("B1")).isEqualTo("FAILED");
        assertThat(count(mst + ".customer") + count(mst + ".account") + count(mst + ".txn")).isZero();
        assertThat(fx.partitions(mst, "txn")).isEmpty();
        assertThat(rejects("B1", "CUSTOMER", "CUS-002")).isEqualTo(1);
        assertThat(rejects("B1", "ACCOUNT", "ACC-003")).isEqualTo(1);
        assertThat(rejects("B1", "TXN", "TXN-006")).isEqualTo(1);
        assertThat(rejects("B1", "TXN", "TXN-004")).isEqualTo(1);
        assertThat(rejects("B1", "TXN", "TXN-002")).isEqualTo(1);
        assertThat(rejects("B1", "TXN", "TXN-005")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CAST(sample_keys AS text) FROM " + aml + ".load_reject WHERE batch_id = 'B1' AND rule_id = 'TXN-002'", String.class)).contains("T5");
        assertThat(jdbc.queryForObject("SELECT reason FROM " + aml + ".load_reject WHERE batch_id = 'B1' AND rule_id = 'TXN-004'", String.class)).startsWith("TXN-004:");
        assertThat(jdbc.queryForObject("SELECT rejected_cnt FROM " + aml + ".load_batch_entity WHERE batch_id = 'B1' AND entity = 'TXN'", Long.class)).isEqualTo(4);
        assertThat(count(stg + ".txn")).as("staged rows are never modified or removed on failure").isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT error_msg FROM " + aml + ".load_batch WHERE batch_id = 'B1'", String.class)).contains("failed validation");
    }

    @Test
    @Req("REQ-BAT-003")
    void failureDuringTheMasterStepLeavesMasterUnchanged() {
        validBatch("B1", D1, "T1", "10");
        jdbc.execute("CREATE OR REPLACE FUNCTION " + mst + ".boom() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'simulated failure'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_acct BEFORE INSERT ON " + mst + ".account FOR EACH ROW EXECUTE FUNCTION " + mst + ".boom()");

        assertThatThrownBy(() -> service.promote("B1")).hasMessageContaining("simulated failure");

        jdbc.execute("DROP TRIGGER fail_acct ON " + mst + ".account");
        assertThat(count(mst + ".customer") + count(mst + ".account") + count(mst + ".txn")).as("customer upsert rolled back too").isZero();
        assertThat(fx.partitions(mst, "txn")).isEmpty();
        assertThat(fx.exists(mst + ".txn_new_" + seq("B1"))).as("the offline-built partition is cleaned up").isFalse();
        assertThat(batchStatus("B1")).isEqualTo("FAILED");
        assertThat(count(stg + ".txn")).as("staged data untouched").isEqualTo(1);
    }

    @Test
    @Req("REQ-BAT-005")
    void cleanDropsAFailedBatchesStagingPartitions() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "ALIEN", "Bad");
        service.promote("B1");
        assertThat(stagingExists("B1")).isTrue();

        service.clean("B1");

        assertThat(stagingExists("B1")).isFalse();
        assertThat(batchStatus("B1")).isEqualTo("FAILED");
        assertThatCode(() -> service.clean("B1")).doesNotThrowAnyException();   // idempotent
        batch("B2", D1, "LOADED");
        assertThatThrownBy(() -> service.clean("B2")).hasMessageContaining("needs FAILED or PROMOTED");
    }

    @Test
    @Req("REQ-BAT-006")
    void laterBatchForSameDateSwapsInThePostingDaysPartition() {
        validBatch("B1", D1, "T1", "100", "T2", "200");
        service.promote("B1");

        validBatch("B2", D1, "T1", "150", "T3", "300");   // corrected delivery of the same business date
        BatchService.Result r = service.promote("B2");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(fx.partitions(mst, "txn")).containsExactly("txn_20260930");
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

        validBatch("B2", D2, "T1", "10", "T9", "5", "T9", "6");   // T1 is in yesterday's partition; T9 twice in this batch
        BatchService.Result r = service.promote("B2");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(rejects("B2", "TXN", "TXN-009")).isEqualTo(1);
        assertThat(rejects("B2", "TXN", "TXN-008")).isEqualTo(2);
    }

    @Test
    @Req("REQ-BAT-011")
    void transactionsMustBePostedOnTheBatchDateMinusTheOffset() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "INDIVIDUAL", "Ada");
        account("B1", "A1", "C1", "DEPOSIT", "ACTIVE");
        txnOn("B1", "2026-09-30", "T1", "A1", "CASH_DEPOSIT", "CREDIT", "10");   // D1 minus 1: fine
        txnOn("B1", "2026-10-01", "T2", "A1", "CASH_DEPOSIT", "CREDIT", "10");   // the business date itself: wrong day
        txnOn("B1", "2026-09-29", "T3", "A1", "CASH_DEPOSIT", "CREDIT", "10");   // a day too early

        BatchService.Result r = service.promote("B1");

        assertThat(r.outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(rejects("B1", "TXN", "TXN-010")).isEqualTo(2);
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
        assertThat(fx.partitions(mst, "txn")).containsExactly("txn_20260930", "txn_20261001");
    }

    @Test
    @Req("REQ-BAT-008")
    void reopenLetsCorrectedStagingRowsBePromoted_butNotAfterTheStagingWasCleaned() {
        batch("B1", D1, "LOADED");
        customer("B1", "C1", "ALIEN", "Ada");
        assertThat(service.promote("B1").outcome()).isEqualTo(BatchService.Outcome.FAILED);

        jdbc.update("UPDATE " + stg + ".customer SET customer_type = 'INDIVIDUAL' WHERE customer_id = 'C1'");   // ETL fixes the row
        service.reopen("B1");
        assertThat(batchStatus("B1")).isEqualTo("LOADED");
        assertThat(count(aml + ".load_reject")).isZero();

        assertThat(service.promote("B1").outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(count(mst + ".customer")).isEqualTo(1);

        batch("B2", D2, "LOADED");
        customer("B2", "C1", "ALIEN", "Ada");
        service.promote("B2");
        service.clean("B2");
        assertThatThrownBy(() -> service.reopen("B2")).hasMessageContaining("already cleaned");
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
