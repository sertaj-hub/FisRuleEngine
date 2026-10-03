package com.fisre.engine.promotion;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.db.Dialect;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes aml.load_batch, aml.load_batch_entity and aml.load_reject. */
@Repository
public class BatchRepository {

    public record Batch(String batchId, long seq, LocalDate businessDate, String status, boolean cleaned) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final Dialect dialect;
    private final String batch;
    private final String batchEntity;
    private final String reject;

    public BatchRepository(NamedParameterJdbcTemplate jdbc, Dialect dialect, FisreProperties props) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.batch = props.schemas().aml() + ".load_batch";
        this.batchEntity = props.schemas().aml() + ".load_batch_entity";
        this.reject = props.schemas().aml() + ".load_reject";
    }

    public Batch get(String batchId) {
        List<Batch> rows = jdbc.query("SELECT batch_id, batch_seq, business_date, status, cleaned_ts FROM " + batch + " WHERE batch_id = :id",
                Map.of("id", batchId),
                (rs, i) -> new Batch(rs.getString(1), rs.getLong(2), rs.getDate(3).toLocalDate(), rs.getString(4), rs.getTimestamp(5) != null));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Unknown batch_id '" + batchId + "'");
        }
        return rows.get(0);
    }

    /** LOADED batches, oldest business date first. */
    public List<String> loadedBatchIds() {
        return jdbc.queryForList("SELECT batch_id FROM " + batch + " WHERE status = 'LOADED' ORDER BY business_date, batch_seq", Map.of(), String.class);
    }

    /** Atomically moves a batch from {@code from} to {@code to}; fails if it is in any other status. */
    public void transition(String batchId, String from, String to) {
        int n = jdbc.update("UPDATE " + batch + " SET status = :to" + ("PROMOTING".equals(to) ? ", claimed_ts = " + dialect.now() : "")
                + " WHERE batch_id = :id AND status = :from",
                Map.of("id", batchId, "from", from, "to", to));
        if (n != 1) {
            throw new IllegalStateException("Batch '" + batchId + "' is in status " + get(batchId).status() + ", expected " + from);
        }
    }

    /** The live (promoted) batch for a business date, other than {@code excluding}. */
    public Optional<String> liveBatch(LocalDate businessDate, String excluding) {
        return jdbc.query("SELECT batch_id FROM " + batch + " WHERE business_date = :d AND status IN ('PROMOTED', 'CLEANED')"
                        + " AND batch_id <> :id", Map.of("d", java.sql.Date.valueOf(businessDate), "id", excluding),
                (rs, i) -> rs.getString(1)).stream().findFirst();
    }

    public void fail(String batchId, String message) {
        String msg = message.substring(0, Math.min(message.length(), 2000));
        jdbc.update("UPDATE " + batch + " SET status = 'FAILED', error_msg = :m WHERE batch_id = :id",
                Map.of("id", batchId, "m", msg));
    }

    public void promoted(String batchId) {
        jdbc.update("UPDATE " + batch + " SET status = 'PROMOTED', error_msg = NULL, promoted_ts = " + dialect.now()
                + " WHERE batch_id = :id", Map.of("id", batchId));
    }

    public void superseded(String oldBatchId, String byBatchId) {
        jdbc.update("UPDATE " + batch + " SET status = 'SUPERSEDED', superseded_by = :by WHERE batch_id = :id",
                Map.of("id", oldBatchId, "by", byBatchId));
    }

    public void cleaned(String batchId) {
        jdbc.update("UPDATE " + batch + " SET cleaned_ts = " + dialect.now()
                + ", status = CASE WHEN status = 'PROMOTED' THEN 'CLEANED' ELSE status END WHERE batch_id = :id",
                Map.of("id", batchId));
    }

    /** Back to LOADED with no counts or reject records. */
    public void reopen(String batchId) {
        jdbc.update("UPDATE " + batch + " SET status = 'LOADED', error_msg = NULL WHERE batch_id = :id", Map.of("id", batchId));
        clearResults(batchId);
    }

    public void clearResults(String batchId) {
        jdbc.update("DELETE FROM " + reject + " WHERE batch_id = :id", Map.of("id", batchId));
        jdbc.update("DELETE FROM " + batchEntity + " WHERE batch_id = :id", Map.of("id", batchId));
    }

    public void saveCounts(String batchId, String entity, long staged, long rejected) {
        jdbc.update("INSERT INTO " + batchEntity + " (batch_id, entity, staged_cnt, rejected_cnt, promoted_cnt)"
                + " VALUES (:id, :e, :s, :r, 0)", new MapSqlParameterSource(Map.of("id", batchId, "e", entity, "s", staged, "r", rejected)));
    }

    public void saveReject(String batchId, String entity, String ruleId, String reason, long count, String sampleKeysJson) {
        jdbc.update("INSERT INTO " + reject + " (batch_id, entity, rule_id, reason, reject_count, sample_keys)"
                + " VALUES (:id, :e, :r, :reason, :n, CAST(:keys AS jsonb))",
                new MapSqlParameterSource().addValue("id", batchId).addValue("e", entity).addValue("r", ruleId)
                        .addValue("reason", reason).addValue("n", count).addValue("keys", sampleKeysJson));
    }

    public void savePromoted(String batchId, String entity, long promoted) {
        jdbc.update("UPDATE " + batchEntity + " SET promoted_cnt = :p WHERE batch_id = :id AND entity = :e",
                Map.of("id", batchId, "e", entity, "p", promoted));
    }

    public record Volume(long days, double average) {}

    /** Promoted transaction counts of the live batches for the {@code days} business days before {@code businessDate}. */
    public Volume trailingTxnVolume(LocalDate businessDate, int days) {
        return jdbc.query("SELECT COUNT(*), COALESCE(AVG(e.promoted_cnt), 0) FROM " + batch + " b JOIN " + batchEntity
                        + " e ON e.batch_id = b.batch_id AND e.entity = 'TXN' WHERE b.status IN ('PROMOTED', 'CLEANED')"
                        + " AND b.business_date < :d AND b.business_date >= :from",
                new MapSqlParameterSource().addValue("d", java.sql.Date.valueOf(businessDate))
                        .addValue("from", java.sql.Date.valueOf(businessDate.minusDays(days))),
                rs -> {
                    rs.next();
                    return new Volume(rs.getLong(1), rs.getDouble(2));
                });
    }
}
