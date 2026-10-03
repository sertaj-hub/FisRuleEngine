package com.fisre.engine.promotion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.db.Dialect;
import com.fisre.engine.db.Entity;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Promotes one batch (one posting day) from stg to mst, all or nothing (specs/requirements/batch.md, ADR-0005):
 * <ol>
 *   <li>claim: LOADED to PROMOTING (atomic)</li>
 *   <li>validate the whole batch with one scan per entity; staged rows are never modified; any reject fails the batch</li>
 *   <li>build the day's transaction partition offline: insert, then primary key and account index, then ANALYZE</li>
 *   <li>in ONE transaction: upsert customers and accounts, swap the new partition in for the old one, mark the batch promoted</li>
 *   <li>drop the batch's staging partitions (separate step)</li>
 * </ol>
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    /** Serializes the short customer/account upsert + partition swap step across concurrent batches. */
    private static final long REFERENCE_LOCK = 7_340_001L;

    public enum Outcome { PROMOTED, FAILED }

    public record Result(String batchId, Outcome outcome, String message) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Dialect dialect;
    private final FisreProperties.Schemas schemas;
    private final FisreProperties.Tuning tuning;
    private final BatchRepository batches;

    public BatchService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Dialect dialect,
                        FisreProperties props, BatchRepository batches) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.dialect = dialect;
        this.schemas = props.schemas();
        this.tuning = props.tuning();
        this.batches = batches;
    }

    // ---- names ----------------------------------------------------------------------------------

    private String stgPartition(Entity e, long seq) {
        return schemas.stg() + "." + e.table() + "_b" + seq;
    }

    private String txnPartitionName(LocalDate posting) {
        return "txn_" + posting.format(YMD);
    }

    // ---- promote --------------------------------------------------------------------------------

    public Result promote(String batchId) {
        BatchRepository.Batch batch = batches.get(batchId);
        batches.transition(batchId, "LOADED", "PROMOTING");
        LocalDate posting = batch.businessDate().minusDays(tuning.postingOffsetDays());
        String prior = batches.liveBatch(batch.businessDate(), batchId).orElse("");
        String tmp = schemas.mst() + ".txn_new_" + batch.seq();
        try {
            batches.clearResults(batchId);
            String problem = validate(batch, posting);
            if (problem != null) {
                batches.fail(batchId, problem);
                log.warn("Batch {} failed validation: {}", batchId, problem);
                return new Result(batchId, Outcome.FAILED, problem);
            }
            long txns = buildTxnPartition(batch, posting, tmp);
            tx.executeWithoutResult(s -> load(batch, prior, posting, tmp, txns));
        } catch (RuntimeException e) {
            dropQuietly(tmp);
            batches.fail(batchId, e.toString());
            throw e;
        }
        log.info("Batch {} promoted{}", batchId, prior.isEmpty() ? "" : " (replacing " + prior + ")");
        clean(batchId);
        return new Result(batchId, Outcome.PROMOTED, null);
    }

    /**
     * Promotes every LOADED batch in business-date order: the earliest alone (it carries the reference snapshot),
     * then the rest in parallel. Stops at once if the first fails; returns all results.
     */
    public List<Result> promoteLoaded() {
        List<String> ids = batches.loadedBatchIds();
        List<Result> results = new ArrayList<>();
        if (ids.isEmpty()) {
            log.info("No LOADED batches");
            return results;
        }
        results.add(promote(ids.get(0)));
        if (results.get(0).outcome() == Outcome.FAILED || ids.size() == 1) {
            return results;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, tuning.parallelism()));
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (String id : ids.subList(1, ids.size())) {
                futures.add(pool.submit(() -> promote(id)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    results.add(new Result(ids.get(i + 1), Outcome.FAILED, String.valueOf(e.getCause())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        } finally {
            pool.shutdown();
        }
        return results;
    }

    // ---- clean / reopen -------------------------------------------------------------------------

    /** Drops the staging partitions of a FAILED batch (so the ETL can reload under a new id) or of a PROMOTED batch whose cleanup did not finish. */
    public void clean(String batchId) {
        BatchRepository.Batch b = batches.get(batchId);
        if (!Set.of("FAILED", "PROMOTED").contains(b.status())) {
            throw new IllegalStateException("Batch '" + batchId + "' is in status " + b.status() + "; clean needs FAILED or PROMOTED");
        }
        for (Entity e : Entity.PROMOTION_ORDER) {
            jdbc.getJdbcOperations().execute("DROP TABLE IF EXISTS " + stgPartition(e, b.seq()));
        }
        batches.cleaned(batchId);
        log.info("Batch {} staging partitions dropped", batchId);
    }

    /** Puts a FAILED (or crashed PROMOTING) batch back to LOADED, e.g. after the ETL corrected its staging rows. */
    public void reopen(String batchId) {
        BatchRepository.Batch b = batches.get(batchId);
        if (!Set.of("FAILED", "PROMOTING").contains(b.status())) {
            throw new IllegalStateException("Batch '" + batchId + "' is in status " + b.status() + "; reopen needs FAILED or PROMOTING");
        }
        if (b.cleaned()) {
            throw new IllegalStateException("Batch '" + batchId + "' staging was already cleaned; reload under a new batch id");
        }
        dropQuietly(schemas.mst() + ".txn_new_" + b.seq());
        batches.reopen(batchId);
    }

    // ---- validation -----------------------------------------------------------------------------

    /** Counts rejects per rule into aml.load_reject and returns a failure message, or null if the whole batch is clean. */
    private String validate(BatchRepository.Batch b, LocalDate posting) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("posting", java.sql.Date.valueOf(posting))
                .addValue("lb_start", java.sql.Date.valueOf(posting.minusDays(tuning.duplicateLookbackDays())))
                .addValue("n", tuning.rejectSampleSize());
        Map<String, String> reasons = ValidationRule.ALL.stream().collect(Collectors.toMap(ValidationRule::id, ValidationRule::reason));
        long staged = 0;
        long rejected = 0;
        long stagedTxns = 0;
        for (Entity e : Entity.PROMOTION_ORDER) {
            String part = stgPartition(e, b.seq());
            jdbc.getJdbcOperations().execute("ANALYZE " + part);
            long s = jdbc.queryForObject("SELECT COUNT(*) FROM " + part, Map.of(), Long.class);
            // Row-local rules (no sub-select) share one scan via a CASE expression: the first failing rule wins.
            List<ValidationRule> local = ValidationRule.ALL.stream().filter(x -> x.entity() == e && x.rowLocal()).toList();
            Map<String, Long> counts = new LinkedHashMap<>();
            if (!local.isEmpty()) {
                String rule = caseExpr(local, b.seq());
                jdbc.query("SELECT rule_id, COUNT(*) AS n FROM (SELECT " + rule + " AS rule_id FROM " + part + " s) x"
                        + " WHERE rule_id IS NOT NULL GROUP BY rule_id", params, rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
            }
            // Rules that look at other tables get their own top-level WHERE so PostgreSQL plans them as joins
            // (inside a CASE they would run once per row). A row can be counted under more than one rule.
            for (ValidationRule rule : ValidationRule.ALL) {
                if (rule.entity() == e && !rule.rowLocal()) {
                    long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + part + " s " + expand(rule.joins(), b.seq()) + " WHERE " + expand(rule.rejectWhen(), b.seq()), params, Long.class);
                    if (n > 0) {
                        counts.put(rule.id(), n);
                    }
                }
            }
            long r = counts.values().stream().mapToLong(Long::longValue).sum();
            if (r > 0) {
                counts.forEach((id, n) -> {
                    ValidationRule rule = ValidationRule.ALL.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
                    List<String> keys = jdbc.queryForList("SELECT CAST(s." + e.keyColumn() + " AS text) FROM " + part + " s " + expand(rule.joins(), b.seq())
                            + " WHERE " + expand(rule.rejectWhen(), b.seq()) + " LIMIT :n", params, String.class);
                    batches.saveReject(b.batchId(), e.name(), id, id + ": " + reasons.get(id), n, toJson(keys));
                });
            }
            batches.saveCounts(b.batchId(), e.name(), s, r);
            staged += s;
            rejected += r;
            if (e == Entity.TXN) {
                stagedTxns = s;
            }
        }
        if (staged == 0) {
            return "Batch is empty: no staged rows";
        }
        if (rejected > 0) {
            return rejected + " staged row(s) failed validation; see aml.load_reject";
        }
        return volumeProblem(b, stagedTxns);
    }

    /**
     * A feed cut short would pass every row check and silently miss alerts, so compare the day's transaction count with the
     * trailing average of recent promoted days (REQ-BAT-012). Quiet until enough history exists.
     */
    private String volumeProblem(BatchRepository.Batch b, long stagedTxns) {
        if (tuning.volumeCheckDays() <= 0) {
            return null;
        }
        BatchRepository.Volume v = batches.trailingTxnVolume(b.businessDate(), tuning.volumeCheckDays());
        if (v.days() < tuning.volumeCheckMinDays() || v.average() <= 0) {
            return null;
        }
        if (stagedTxns * 100.0 < v.average() * tuning.volumeLowPercent()) {
            String reason = String.format("VOL-001: %d transactions is below %d%% of the %d-day average of %.0f", stagedTxns,
                    tuning.volumeLowPercent(), v.days(), v.average());
            batches.saveReject(b.batchId(), Entity.TXN.name(), "VOL-001", reason, 1, "[]");
            return "Volume check failed: " + reason.substring("VOL-001: ".length());
        }
        if (stagedTxns * 100.0 > v.average() * tuning.volumeHighPercent()) {
            log.warn("Batch {} has {} transactions, above {}% of the {}-day average of {}; check the feed for duplicates",
                    b.batchId(), stagedTxns, tuning.volumeHighPercent(), v.days(), Math.round(v.average()));
        }
        return null;
    }

    /** CASE expression returning the id of the first of the given rules that a staged row breaks, or NULL. */
    private String caseExpr(List<ValidationRule> rules, long seq) {
        StringBuilder sb = new StringBuilder("CASE");
        for (ValidationRule rule : rules) {
            sb.append(" WHEN ").append(expand(rule.rejectWhen(), seq)).append(" THEN '").append(rule.id()).append("'");
        }
        return sb.append(" END").toString();
    }

    private String expand(String sql, long seq) {
        String out = sql.replace("{mst}", schemas.mst());
        for (Entity e : Entity.PROMOTION_ORDER) {
            out = out.replace("{stg." + e.table() + "}", stgPartition(e, seq));
        }
        return out;
    }

    private static String toJson(List<String> keys) {
        try {
            return JSON.writeValueAsString(keys);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- loading --------------------------------------------------------------------------------

    /** Builds the day's partition as a standalone table: bulk insert first, then primary key and index, then statistics. */
    private long buildTxnPartition(BatchRepository.Batch b, LocalDate posting, String tmp) {
        var ops = jdbc.getJdbcOperations();
        ops.execute("DROP TABLE IF EXISTS " + tmp);
        ops.execute("CREATE TABLE " + tmp + " (LIKE " + schemas.mst() + ".txn INCLUDING DEFAULTS, CONSTRAINT ck_txn_new_" + b.seq()
                + " CHECK (posting_date = DATE '" + posting + "'))");
        String cols = String.join(", ", Entity.TXN.columns());
        long n = jdbc.update("INSERT INTO " + tmp + " (" + cols + ", batch_id) SELECT " + cols + ", batch_id FROM "
                + stgPartition(Entity.TXN, b.seq()), Map.of());
        ops.execute("ALTER TABLE " + tmp + " ADD PRIMARY KEY (transaction_id, posting_date)");
        ops.execute("CREATE INDEX ON " + tmp + " (account_id, posting_date)");
        ops.execute("ANALYZE " + tmp);
        return n;
    }

    private void load(BatchRepository.Batch b, String prior, LocalDate posting, String tmp, long txns) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(:k)::text", Map.of("k", REFERENCE_LOCK), String.class);
        for (Entity e : List.of(Entity.CUSTOMER, Entity.ACCOUNT)) {
            String part = stgPartition(e, b.seq());
            String cols = String.join(", ", e.columns());
            // Latest staged row per key wins if a key repeats within the batch. DISTINCT ON sorts once; a correlated
            // MAX() per row would scan the unindexed staging partition for every row.
            String latest = "SELECT DISTINCT ON (" + e.keyColumn() + ") " + cols + ", batch_id FROM " + part
                    + " ORDER BY " + e.keyColumn() + ", stg_id DESC";
            batches.savePromoted(b.batchId(), e.name(), jdbc.update(dialect.upsert(schemas.mst() + "." + e.table(), e, latest), Map.of()));
        }
        String partName = txnPartitionName(posting);
        String qualified = schemas.mst() + "." + partName;
        if (jdbc.queryForObject("SELECT to_regclass(:n) IS NOT NULL", Map.of("n", qualified), Boolean.class)) {
            jdbc.getJdbcOperations().execute("ALTER TABLE " + schemas.mst() + ".txn DETACH PARTITION " + qualified);
            jdbc.getJdbcOperations().execute("DROP TABLE " + qualified);
        }
        jdbc.getJdbcOperations().execute("ALTER TABLE " + tmp + " RENAME TO " + partName);
        jdbc.getJdbcOperations().execute("ALTER TABLE " + schemas.mst() + ".txn ATTACH PARTITION " + qualified
                + " FOR VALUES FROM ('" + posting + "') TO ('" + posting.plusDays(1) + "')");
        batches.savePromoted(b.batchId(), Entity.TXN.name(), txns);
        if (!prior.isEmpty()) {
            batches.superseded(prior, b.batchId());
        }
        batches.promoted(b.batchId());
    }

    private void dropQuietly(String table) {
        try {
            jdbc.getJdbcOperations().execute("DROP TABLE IF EXISTS " + table);
        } catch (RuntimeException e) {
            log.warn("Could not drop {}: {}", table, e.getMessage());
        }
    }
}
