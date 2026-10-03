package com.fisre.engine.promotion;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.db.Dialect;
import com.fisre.engine.db.Entity;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Promotes one batch from stg to mst, all or nothing, with set-based SQL (specs/requirements/batch.md):
 * <ol>
 *   <li>claim: LOADED to PROMOTING (atomic, so two runs cannot overlap)</li>
 *   <li>validate the whole batch; any reject (or an empty batch) fails it and nothing reaches master</li>
 *   <li>load in ONE transaction: replace the earlier live batch for the date, upsert customer and account, insert txn</li>
 *   <li>clean the batch's stg rows (separate transaction, so a slow delete never undoes a good promotion)</li>
 * </ol>
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    public enum Outcome { PROMOTED, FAILED }

    public record Result(String batchId, Outcome outcome, String message) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Dialect dialect;
    private final FisreProperties.Schemas schemas;
    private final BatchRepository batches;

    public BatchService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Dialect dialect,
                        FisreProperties props, BatchRepository batches) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.dialect = dialect;
        this.schemas = props.schemas();
        this.batches = batches;
    }

    public Result promote(String batchId) {
        BatchRepository.Batch batch = batches.get(batchId);
        batches.transition(batchId, "LOADED", "PROMOTING");
        String prior = batches.liveBatch(batch.businessDate(), batchId).orElse("");
        try {
            String problem = tx.execute(s -> validate(batchId, prior));
            if (problem != null) {
                batches.fail(batchId, problem);
                log.warn("Batch {} failed validation: {}", batchId, problem);
                return new Result(batchId, Outcome.FAILED, problem);
            }
            tx.executeWithoutResult(s -> load(batchId, prior));
        } catch (RuntimeException e) {
            batches.fail(batchId, e.toString());
            throw e;
        }
        log.info("Batch {} promoted{}", batchId, prior.isEmpty() ? "" : " (replacing " + prior + ")");
        clean(batchId);
        return new Result(batchId, Outcome.PROMOTED, null);
    }

    /** Deletes the stg rows of a FAILED batch (so the ETL can reload) or of a PROMOTED batch whose cleanup did not finish. */
    public void clean(String batchId) {
        String status = batches.get(batchId).status();
        if (!Set.of("FAILED", "PROMOTED").contains(status)) {
            throw new IllegalStateException("Batch '" + batchId + "' is in status " + status + "; clean needs FAILED or PROMOTED");
        }
        tx.executeWithoutResult(s -> {
            for (Entity e : Entity.PROMOTION_ORDER) {
                jdbc.update("DELETE FROM " + schemas.stg() + "." + e.table() + " WHERE batch_id = :b", Map.of("b", batchId));
            }
            batches.cleaned(batchId);
        });
        log.info("Batch {} staging rows deleted", batchId);
    }

    /** Puts a FAILED (or crashed PROMOTING) batch back to LOADED, clearing reject reasons, e.g. after the ETL corrected stg rows. */
    public void reopen(String batchId) {
        String status = batches.get(batchId).status();
        if (!Set.of("FAILED", "PROMOTING").contains(status)) {
            throw new IllegalStateException("Batch '" + batchId + "' is in status " + status + "; reopen needs FAILED or PROMOTING");
        }
        tx.executeWithoutResult(s -> {
            for (Entity e : Entity.PROMOTION_ORDER) {
                jdbc.update("UPDATE " + schemas.stg() + "." + e.table() + " SET reject_reason = NULL WHERE batch_id = :b",
                        Map.of("b", batchId));
            }
            batches.reopen(batchId);
        });
    }

    /** Marks rejects in stg and returns a failure message, or null if the whole batch is clean. */
    private String validate(String batchId, String prior) {
        Map<String, Object> params = Map.of("batch", batchId, "prior", prior);
        long staged = 0;
        long rejected = 0;
        for (Entity e : Entity.PROMOTION_ORDER) {
            String stg = schemas.stg() + "." + e.table();
            for (ValidationRule rule : ValidationRule.ALL) {
                if (rule.entity() != e) {
                    continue;
                }
                String sql = "UPDATE " + stg + " s SET reject_reason = :reason WHERE s.batch_id = :batch"
                        + " AND s.reject_reason IS NULL AND "
                        + rule.rejectWhen().replace("{mst}", schemas.mst()).replace("{stg}", schemas.stg());
                jdbc.update(sql, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(params)
                        .addValue("reason", rule.id() + ": " + rule.reason()));
            }
            long s = count(stg, "batch_id = :batch", params);
            long r = count(stg, "batch_id = :batch AND reject_reason IS NOT NULL", params);
            batches.saveCounts(batchId, e.name(), s, r);
            staged += s;
            rejected += r;
        }
        if (staged == 0) {
            return "Batch is empty: no staged rows";
        }
        return rejected == 0 ? null : rejected + " staged row(s) failed validation; see stg reject_reason";
    }

    private void load(String batchId, String prior) {
        Map<String, Object> params = Map.of("batch", batchId);
        if (!prior.isEmpty()) {
            jdbc.update("DELETE FROM " + schemas.mst() + ".txn WHERE batch_id = :prior", Map.of("prior", prior));
            batches.superseded(prior, batchId);
        }
        for (Entity e : Entity.PROMOTION_ORDER) {
            String stg = schemas.stg() + "." + e.table();
            String mst = schemas.mst() + "." + e.table();
            String cols = String.join(", ", e.columns());
            String source = "SELECT " + cols + ", batch_id FROM " + stg + " s WHERE s.batch_id = :batch AND s.reject_reason IS NULL";
            long n;
            if (e == Entity.TXN) {
                n = jdbc.update("INSERT INTO " + mst + " (" + cols + ", batch_id) " + source, params);
            } else {
                // Latest staged row per key wins if a key repeats within the batch.
                String latest = source + " AND s.stg_id = (SELECT MAX(x.stg_id) FROM " + stg + " x WHERE x.batch_id = s.batch_id"
                        + " AND x.reject_reason IS NULL AND x." + e.keyColumn() + " = s." + e.keyColumn() + ")";
                n = jdbc.update(dialect.upsert(mst, e, latest), params);
            }
            batches.savePromoted(batchId, e.name(), n);
        }
        batches.promoted(batchId);
    }

    private long count(String table, String where, Map<String, Object> params) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, params, Long.class);
    }
}
