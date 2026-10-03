package com.fisre.engine.run;

import com.fisre.engine.detect.DeliveryService;
import com.fisre.engine.detect.DetectionService;
import com.fisre.engine.promotion.BatchRepository;
import com.fisre.engine.promotion.BatchService;
import com.fisre.engine.promotion.RetentionService;
import com.fisre.engine.config.FisreProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The nightly sequence for one batch and business date: promote, detect, retain (specs/requirements/platform.md).
 * Each step is recorded in aml.nightly_run. The first failing step stops the run; later steps are recorded SKIPPED.
 * A retry after a later step failed skips promote when the batch is already promoted.
 */
@Service
public class NightlyService {

    private static final Logger log = LoggerFactory.getLogger(NightlyService.class);

    public record Step(String step, String status, String message) {
        public boolean failed() {
            return "FAILED".equals(status);
        }
    }

    /** What a step reports: null message means plain success. */
    private record Outcome(String status, String message) {
        static Outcome ok() { return new Outcome("SUCCESS", null); }
        static Outcome failed(String m) { return new Outcome("FAILED", m); }
        static Outcome skipped(String m) { return new Outcome("SKIPPED", m); }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final BatchService batches;
    private final BatchRepository batchRepo;
    private final DetectionService detection;
    private final RetentionService retention;
    private final DeliveryService deliveries;
    private final String aml;

    public NightlyService(NamedParameterJdbcTemplate jdbc, BatchService batches, BatchRepository batchRepo,
                          DetectionService detection, RetentionService retention, DeliveryService deliveries, FisreProperties props) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.batchRepo = batchRepo;
        this.detection = detection;
        this.retention = retention;
        this.deliveries = deliveries;
        this.aml = props.schemas().aml();
    }

    public List<Step> run(String batchId, LocalDate date) {
        BatchRepository.Batch batch = batchRepo.get(batchId);
        if (!batch.businessDate().equals(date)) {
            throw new IllegalArgumentException("Batch '" + batchId + "' is for business date " + batch.businessDate() + ", not " + date);
        }
        List<Step> steps = new ArrayList<>();
        boolean stopped = false;
        for (Map.Entry<String, Supplier<Outcome>> s : List.<Map.Entry<String, Supplier<Outcome>>>of(
                Map.entry("PROMOTE", () -> promote(batchId)),
                Map.entry("DETECT", () -> detect(date)),
                Map.entry("RETAIN", () -> retain(date)))) {
            long id = start(date, batchId, s.getKey(), stopped);
            Outcome o = stopped ? Outcome.skipped("an earlier step failed") : execute(s.getValue());
            finish(id, o);
            steps.add(new Step(s.getKey(), o.status(), o.message()));
            log.info("Nightly {} {}: {}{}", date, s.getKey(), o.status(), o.message() == null ? "" : " (" + o.message() + ")");
            stopped = stopped || "FAILED".equals(o.status());
        }
        return steps;
    }

    private Outcome execute(Supplier<Outcome> step) {
        try {
            return step.get();
        } catch (RuntimeException e) {
            return Outcome.failed(e.toString());
        }
    }

    private Outcome promote(String batchId) {
        String status = batchRepo.get(batchId).status();
        if (Set.of("PROMOTED", "CLEANED").contains(status)) {
            return Outcome.skipped("batch already promoted");
        }
        BatchService.Result r = batches.promote(batchId);
        return r.outcome() == BatchService.Outcome.PROMOTED ? Outcome.ok() : Outcome.failed(r.message());
    }

    private Outcome detect(LocalDate date) {
        DetectionService.Result r = detection.detect(date);
        return r.rulesFailed() == 0 ? new Outcome("SUCCESS", deliveries.describeLatest(date).orElse(null))
                : Outcome.failed(r.rulesFailed() + " of " + r.rulesRun() + " rule(s) failed; see aml.rule_run");
    }

    private Outcome retain(LocalDate date) {
        retention.retain(date);
        return Outcome.ok();
    }

    private long start(LocalDate date, String batchId, String step, boolean skipped) {
        return jdbc.queryForObject("INSERT INTO " + aml + ".nightly_run (business_date, batch_id, step, status) VALUES (:d, :b, :s, :st) RETURNING run_id",
                new MapSqlParameterSource().addValue("d", java.sql.Date.valueOf(date)).addValue("b", batchId)
                        .addValue("s", step).addValue("st", skipped ? "SKIPPED" : "RUNNING"), Long.class);
    }

    private void finish(long id, Outcome o) {
        String msg = o.message() == null ? null : o.message().substring(0, Math.min(o.message().length(), 2000));
        jdbc.update("UPDATE " + aml + ".nightly_run SET status = :s, message = :m, ended_ts = CURRENT_TIMESTAMP WHERE run_id = :id",
                new MapSqlParameterSource().addValue("s", o.status()).addValue("m", msg).addValue("id", id));
    }
}
