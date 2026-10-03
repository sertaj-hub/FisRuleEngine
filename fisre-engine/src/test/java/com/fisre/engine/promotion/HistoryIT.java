package com.fisre.engine.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.spec.Req;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** History load (promote-loaded), retention and the synthetic data generator. */
@SpringBootTest
class HistoryIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired BatchService service;
    @Autowired RetentionService retention;
    @Autowired SyntheticData synthetic;
    @Autowired FisreProperties props;

    Fixtures fx;

    @BeforeEach
    void reset() {
        fx = new Fixtures(jdbc, props);
        fx.resetAll();
    }

    /** A LOADED batch whose posting day is the day before its business date. */
    void loadedBatch(String id, String businessDate, boolean withReference, String... txnIds) {
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status) VALUES (?, ?, 'LOADED')", id, Date.valueOf(businessDate));
        if (withReference) {
            jdbc.update("INSERT INTO stg.customer (batch_id, customer_id, customer_type, full_name) VALUES (?, 'C1', 'INDIVIDUAL', 'Ada')", id);
            jdbc.update("INSERT INTO stg.account (batch_id, account_id, primary_customer_id, product_type, status, open_date) VALUES (?, 'A1', 'C1', 'DEPOSIT', 'ACTIVE', DATE '2020-01-01')", id);
        }
        String posting = LocalDate.parse(businessDate).minusDays(1).toString();
        for (String t : txnIds) {
            jdbc.update("INSERT INTO stg.txn (batch_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                    + " VALUES (?, ?, 'A1', ?, ?, 'CASH_DEPOSIT', 'CREDIT', ?, 'USD')", id, t, Timestamp.valueOf(posting + " 09:00:00"), Date.valueOf(posting), new BigDecimal("10"));
        }
    }

    @Test
    @Req("REQ-HIS-001")
    void historyIsLoadedEarliestFirstThenTheRestInParallel() {
        loadedBatch("H5", "2026-09-05", false, "T5");
        loadedBatch("H1", "2026-09-01", true, "T1");   // earliest: carries the customer and account snapshot
        loadedBatch("H3", "2026-09-03", false, "T3");
        loadedBatch("H2", "2026-09-02", false, "T2");
        loadedBatch("H4", "2026-09-04", false, "T4");

        List<BatchService.Result> results = service.promoteLoaded();

        assertThat(results).hasSize(5).allSatisfy(r -> assertThat(r.outcome()).isEqualTo(BatchService.Outcome.PROMOTED));
        assertThat(results.get(0).batchId()).as("earliest batch runs alone first").isEqualTo("H1");
        assertThat(fx.partitions("mst", "txn")).containsExactly("txn_20200831".replace("2020", "2026"), "txn_20260901", "txn_20260902", "txn_20260903", "txn_20260904");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mst.txn", Long.class)).isEqualTo(5);
    }

    @Test
    @Req("REQ-HIS-001")
    void ifTheFirstBatchFailsTheRestAreNotStarted() {
        loadedBatch("H1", "2026-09-01", true, "T1");
        jdbc.update("UPDATE stg.customer SET customer_type = 'ALIEN'");        // breaks the snapshot batch
        loadedBatch("H2", "2026-09-02", false, "T2");

        List<BatchService.Result> results = service.promoteLoaded();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT status FROM aml.load_batch WHERE batch_id = 'H2'", String.class)).isEqualTo("LOADED");
    }

    @Test
    @Req("REQ-RET-001")
    void retainDropsPartitionsOlderThanThirteenMonthsAndNoOthers() {
        fx.account("A1", "DEPOSIT", "C1", "2020-01-01");
        for (String d : new String[] {"2024-01-01", "2025-08-29", "2025-08-30", "2026-03-15", "2026-09-30"}) {
            fx.txn("T" + d, "A1", "CASH_DEPOSIT", "CREDIT", "10", d);
        }

        int dropped = retention.retain(LocalDate.parse("2026-10-01"));   // as-of 2026-09-30, cutoff 2025-08-30

        assertThat(dropped).isEqualTo(2);
        assertThat(fx.partitions("mst", "txn")).containsExactly("txn_20250830", "txn_20260315", "txn_20260930");
    }

    @Test
    @Req("REQ-BEN-001")
    void generatedBatchIsValidAndPromotes() {
        synthetic.generate("GEN1", LocalDate.parse("2026-10-01"));

        assertThat(jdbc.queryForObject("SELECT status FROM aml.load_batch WHERE batch_id = 'GEN1'", String.class)).isEqualTo("LOADED");
        BatchService.Result r = service.promote("GEN1");

        assertThat(r.outcome()).as(String.valueOf(r.message())).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mst.txn", Long.class)).isEqualTo(props.bench().txns());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mst.account", Long.class)).isEqualTo(props.bench().accounts());
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT product_type) FROM mst.account", Long.class)).isEqualTo(3);
        // Regression: once every row got the same random account and type. Data must be spread and varied.
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT account_id) FROM mst.txn", Long.class)).isGreaterThan(props.bench().accounts() / 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT txn_type) FROM mst.txn", Long.class)).isGreaterThanOrEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mst.txn t JOIN mst.ref_txn_type r ON r.txn_type = t.txn_type WHERE r.is_cash = 'Y'", Long.class)).isGreaterThan(0);
    }
}
