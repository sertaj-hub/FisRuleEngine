package com.fisre.engine.promotion;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.db.Dialect;
import com.fisre.engine.db.Entity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves NEW staging rows to master, one entity at a time (customer, account, txn), entirely with
 * set-based SQL. Each entity runs in one transaction, so a failed run leaves its rows NEW and
 * a re-run is safe. See specs/requirements/promotion.md.
 */
@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Dialect dialect;
    private final FisreProperties.Schemas schemas;
    private final RunRepository runs;

    public PromotionService(JdbcTemplate jdbc, TransactionTemplate tx, Dialect dialect,
                            FisreProperties props, RunRepository runs) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.dialect = dialect;
        this.schemas = props.schemas();
        this.runs = runs;
    }

    public record EntityResult(String entity, long rejected, long promoted) {}

    public List<EntityResult> promote() {
        String runId = UUID.randomUUID().toString();
        runs.start(runId, "PROMOTE");
        List<EntityResult> results = new ArrayList<>();
        try {
            for (Entity entity : Entity.PROMOTION_ORDER) {
                EntityResult r = tx.execute(status -> promoteEntity(entity));
                results.add(r);
                runs.recordEntity(runId, r);
                log.info("Promoted {}: {} promoted, {} rejected", r.entity(), r.promoted(), r.rejected());
            }
            runs.finish(runId, "SUCCESS", null);
            return results;
        } catch (RuntimeException e) {
            runs.finish(runId, "FAILED", e.toString());
            throw e;
        }
    }

    private EntityResult promoteEntity(Entity e) {
        String stg = schemas.stg() + "." + e.table();
        String mst = schemas.mst() + "." + e.table();

        long rejected = 0;
        for (ValidationRule rule : ValidationRule.ALL) {
            if (rule.entity() != e) {
                continue;
            }
            String sql = "UPDATE " + stg + " s SET rec_status = 'REJECTED', reject_reason = ? "
                    + "WHERE s.rec_status = 'NEW' AND " + rule.rejectWhen().replace("{mst}", schemas.mst());
            rejected += jdbc.update(sql, rule.id() + ": " + rule.reason());
        }

        // Latest NEW row per key wins when the same key appears more than once in a batch.
        String source = "SELECT s." + String.join(", s.", e.columns()) + ", s.load_id FROM " + stg + " s "
                + "WHERE s.rec_status = 'NEW' AND s.stg_id = (SELECT MAX(x.stg_id) FROM " + stg + " x "
                + "WHERE x." + e.keyColumn() + " = s." + e.keyColumn() + " AND x.rec_status = 'NEW')";

        long promoted;
        if (e == Entity.TXN) {
            // Transactions are immutable: first delivery wins, later duplicates are ignored.
            String cols = String.join(", ", e.columns());
            promoted = jdbc.update("INSERT INTO " + mst + " (" + cols + ", load_id) SELECT " + cols
                    + ", load_id FROM (" + source + ") src WHERE NOT EXISTS (SELECT 1 FROM " + mst
                    + " m WHERE m." + e.keyColumn() + " = src." + e.keyColumn() + ")");
        } else {
            promoted = jdbc.update(dialect.upsert(mst, e, source));
        }

        jdbc.update("UPDATE " + stg + " SET rec_status = 'PROCESSED' WHERE rec_status = 'NEW'");
        return new EntityResult(e.name(), rejected, promoted);
    }
}
