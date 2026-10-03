package com.fisre.engine.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.spec.Req;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Feed completeness: a day far below the recent average fails the batch (REQ-BAT-012). */
@SpringBootTest
class VolumeCheckIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired BatchService service;
    @Autowired FisreProperties props;

    @BeforeEach
    void reset() {
        new Fixtures(jdbc, props).resetAll();
    }

    /** Registers a LOADED batch for the business date with {@code txns} cash deposits posted on the day before. */
    String batch(String id, String businessDate, int txns, boolean withReference) {
        jdbc.update("INSERT INTO aml.load_batch (batch_id, business_date, status) VALUES (?, ?::date, 'LOADED')", id, businessDate);
        if (withReference) {
            jdbc.update("INSERT INTO stg.customer (batch_id, customer_id, customer_type, full_name) VALUES (?, 'C1', 'INDIVIDUAL', 'Ada')", id);
            jdbc.update("INSERT INTO stg.account (batch_id, account_id, primary_customer_id, product_type, status, open_date) VALUES (?, 'A1', 'C1', 'DEPOSIT', 'ACTIVE', DATE '2020-01-01')", id);
        }
        String posting = LocalDate.parse(businessDate).minusDays(1).toString();
        jdbc.update("INSERT INTO stg.txn (batch_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                + " SELECT ?, ? || '-' || g, 'A1', ?::timestamp, ?::date, 'CASH_DEPOSIT', 'CREDIT', 10, 'USD' FROM generate_series(1, ?) g",
                id, id, posting + " 10:00:00", posting, txns);
        return id;
    }

    @Test
    @Req("REQ-BAT-012")
    void aDayFarBelowTheTrailingAverageFailsWithVol001_andANormalDayPasses() {
        for (int i = 1; i <= 3; i++) {   // three normal days of 10 transactions build the history
            assertThat(service.promote(batch("H" + i, "2026-09-0" + i, 10, i == 1)).outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        }

        BatchService.Result cut = service.promote(batch("CUT", "2026-09-04", 3, false));   // 3 is below 50% of 10

        assertThat(cut.outcome()).isEqualTo(BatchService.Outcome.FAILED);
        assertThat(cut.message()).contains("Volume check failed").contains("3 transactions").contains("below 50%");
        assertThat(jdbc.queryForObject("SELECT reason FROM aml.load_reject WHERE batch_id = 'CUT' AND rule_id = 'VOL-001'", String.class)).startsWith("VOL-001:");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mst.txn WHERE batch_id = 'CUT'", Long.class)).isZero();

        assertThat(service.promote(batch("OK", "2026-09-04", 6, false)).outcome()).as("6 is at least 50% of 10").isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(service.promote(batch("BIG", "2026-09-05", 40, false)).outcome()).as("far above average only warns").isEqualTo(BatchService.Outcome.PROMOTED);
    }

    @Test
    @Req("REQ-BAT-012")
    void theCheckStaysQuietUntilEnoughHistoryExists() {
        assertThat(service.promote(batch("H1", "2026-09-01", 10, true)).outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
        assertThat(service.promote(batch("H2", "2026-09-02", 10, false)).outcome()).isEqualTo(BatchService.Outcome.PROMOTED);

        // only two days of history, the minimum is three: even a tiny day is accepted
        assertThat(service.promote(batch("TINY", "2026-09-03", 1, false)).outcome()).isEqualTo(BatchService.Outcome.PROMOTED);
    }
}
