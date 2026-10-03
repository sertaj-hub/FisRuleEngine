package com.fisre.engine;

import com.fisre.engine.config.FisreProperties;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test data helper: resets every table and inserts master data directly (as if a batch had been promoted). */
public class Fixtures {

    private final JdbcTemplate jdbc;
    private final String stg, mst, aml;

    public Fixtures(JdbcTemplate jdbc, FisreProperties props) {
        this.jdbc = jdbc;
        this.stg = props.schemas().stg();
        this.mst = props.schemas().mst();
        this.aml = props.schemas().aml();
    }

    public void resetAll() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_txn ON " + mst + ".txn");
        for (String t : new String[] {aml + ".alert", aml + ".rule_run", aml + ".rule", mst + ".txn", mst + ".account", mst + ".customer",
                stg + ".txn", stg + ".account", stg + ".customer", aml + ".load_batch_entity", aml + ".load_batch"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    /** A promoted batch for the date, so detection is allowed to run. */
    public void liveBatch(String batchId, String date) {
        jdbc.update("INSERT INTO " + aml + ".load_batch (batch_id, business_date, status) VALUES (?, ?, 'CLEANED')", batchId, Date.valueOf(date));
    }

    public void customer(String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + mst + ".customer WHERE customer_id = ?", Long.class, id) == 0) {
            jdbc.update("INSERT INTO " + mst + ".customer (customer_id, customer_type, full_name, batch_id) VALUES (?, 'INDIVIDUAL', ?, 'FIX')", id, "Customer " + id);
        }
    }

    public void account(String id, String product, String customerId, String openDate) {
        customer(customerId);
        jdbc.update("INSERT INTO " + mst + ".account (account_id, primary_customer_id, product_type, status, open_date, currency, batch_id)"
                + " VALUES (?, ?, ?, 'ACTIVE', ?, 'USD', 'FIX')", id, customerId, product, Date.valueOf(openDate));
    }

    public void txn(String id, String accountId, String type, String direction, BigDecimal amount, String date, String time) {
        jdbc.update("INSERT INTO " + mst + ".txn (transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency, batch_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'USD', 'FIX')", id, accountId, Timestamp.valueOf(date + " " + time + ":00"),
                Date.valueOf(date), type, direction, amount);
    }

    public void txn(String id, String accountId, String type, String direction, String amount, String date) {
        txn(id, accountId, type, direction, new BigDecimal(amount), date, "12:00");
    }
}
