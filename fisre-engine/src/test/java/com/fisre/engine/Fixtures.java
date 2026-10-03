package com.fisre.engine;

import com.fisre.engine.config.FisreProperties;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test data helper: resets every table (including partitions) and inserts master data directly. */
public class Fixtures {

    private final JdbcTemplate jdbc;
    private final String stg, mst, aml;

    public Fixtures(JdbcTemplate jdbc, FisreProperties props) {
        this.jdbc = jdbc;
        this.stg = props.schemas().stg();
        this.mst = props.schemas().mst();
        this.aml = props.schemas().aml();
    }

    public static FisreProperties props(String job, String batchId, String businessDate) {
        return new FisreProperties("postgresql", job, batchId, businessDate, "specs/rules",
                new FisreProperties.Schemas("stg", "mst", "aml"), FisreProperties.Tuning.defaults(), FisreProperties.Bench.defaults(), FisreProperties.Confirm.defaults());
    }

    /** Names of the partitions of a partitioned table. */
    public List<String> partitions(String schema, String table) {
        return jdbc.queryForList("SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent"
                + " JOIN pg_namespace n ON n.oid = p.relnamespace WHERE n.nspname = ? AND p.relname = ? ORDER BY 1", String.class, schema, table);
    }

    public boolean exists(String qualifiedTable) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, qualifiedTable));
    }

    public void resetAll() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_acct ON " + mst + ".account");
        purgeAlerts();
        purgeRuleAudit();
        jdbc.update("DELETE FROM " + aml + ".rule_user");
        for (String t : new String[] {aml + ".ml_score", aml + ".ml_model", aml + ".alert_rejection_notice", aml + ".alert_delivery", aml + ".rule_run", aml + ".rule", aml + ".nightly_run"}) {
            jdbc.update("DELETE FROM " + t);
        }
        for (String p : partitions(mst, "txn")) {
            jdbc.execute("DROP TABLE " + mst + "." + p);
        }
        for (String leftover : jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name LIKE 'txn\\_new\\_%'", String.class, mst)) {
            jdbc.execute("DROP TABLE " + mst + "." + leftover);
        }
        jdbc.update("DELETE FROM " + mst + ".account");
        jdbc.update("DELETE FROM " + mst + ".customer");
        for (String table : new String[] {"customer", "account", "txn"}) {
            for (String p : partitions(stg, table)) {
                jdbc.execute("DROP TABLE " + stg + "." + p);
            }
        }
        for (String t : new String[] {aml + ".load_reject", aml + ".load_batch_entity", aml + ".load_batch"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    /** The rule audit trail is append-only; tests clear it with the documented bypass, on one connection. */
    public void purgeRuleAudit() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET aml.allow_audit_purge = 'on'");
                st.execute("DELETE FROM " + aml + ".rule_audit");
                st.execute("RESET aml.allow_audit_purge");
            }
            return null;
        });
    }

    /** Registers an ML model row (no artifact; detection only reads scores). */
    public void mlModel(String name, String version, String status) {
        jdbc.update("INSERT INTO " + aml + ".ml_model (model_name, model_version, status, train_from, train_to, train_rows, params, artifact_path, artifact_sha256)"
                + " VALUES (?, ?, ?, DATE '2026-01-01', DATE '2026-06-30', 1000, '{}'::jsonb, '/tmp/none', repeat('0', 64))", name, version, status);
    }

    public void mlScore(String version, String asOf, String accountId, String product, double score, int rank) {
        jdbc.update("INSERT INTO " + aml + ".ml_score (model_version, as_of_date, account_id, product_type, score, rank_in_product, explanation)"
                + " VALUES (?, ?, ?, ?, ?, ?, '[{\"feature\":\"total_1d\",\"value\":9000,\"peer_median\":100,\"z\":12.5}]'::jsonb)",
                version, Date.valueOf(asOf), accountId, product, score, rank);
    }

    /** Handed-off alerts are immutable; tests purge them with the documented bypass, on one connection. */
    public void purgeAlerts() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET aml.allow_alert_purge = 'on'");
                st.execute("DELETE FROM " + aml + ".alert");
                st.execute("RESET aml.allow_alert_purge");
            }
            return null;
        });
    }

    /** A promoted batch for the business date, so detection is allowed to run. */
    public void liveBatch(String batchId, String businessDate) {
        jdbc.update("INSERT INTO " + aml + ".load_batch (batch_id, business_date, status) VALUES (?, ?, 'CLEANED')", batchId, Date.valueOf(businessDate));
    }

    public void ensurePartition(String date) {
        LocalDate d = LocalDate.parse(date);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + mst + ".txn_" + date.replace("-", "") + " PARTITION OF " + mst + ".txn FOR VALUES FROM ('"
                + d + "') TO ('" + d.plusDays(1) + "')");
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

    public void txn(String id, String accountId, String type, String direction, BigDecimal amount, String date, String time, String counterpartyCountry) {
        ensurePartition(date);
        jdbc.update("INSERT INTO " + mst + ".txn (transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency, counterparty_country, batch_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'USD', ?, 'FIX')", id, accountId, Timestamp.valueOf(date + " " + time + ":00"),
                Date.valueOf(date), type, direction, amount, counterpartyCountry);
    }

    public void txn(String id, String accountId, String type, String direction, BigDecimal amount, String date, String time) {
        txn(id, accountId, type, direction, amount, date, time, null);
    }

    public void txn(String id, String accountId, String type, String direction, String amount, String date) {
        txn(id, accountId, type, direction, new BigDecimal(amount), date, "12:00");
    }
}
