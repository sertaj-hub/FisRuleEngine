package com.fisre.engine.run;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.detect.DetectionService;
import com.fisre.engine.promotion.BatchService;
import com.fisre.engine.promotion.RetentionService;
import com.fisre.engine.promotion.SyntheticData;
import com.fisre.engine.rules.RuleLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the job named by fisre.job (FISRE_JOB), then the process exits. Batch jobs (promote, clean, reopen, nightly) need
 * fisre.batch-id; detect needs fisre.business-date; load-rules reads fisre.rules-dir.
 * Any failure, including a batch that fails validation, makes the process exit non-zero.
 */
@Component
public class JobRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final FisreProperties props;
    private final BatchService batches;
    private final RuleLoader rules;
    private final DetectionService detection;
    private final RetentionService retention;
    private final SyntheticData synthetic;
    private final NightlyService nightly;
    private final JobLock lock;
    private final HealthService health;

    public JobRunner(FisreProperties props, BatchService batches, RuleLoader rules, DetectionService detection,
                     RetentionService retention, SyntheticData synthetic, NightlyService nightly, JobLock lock, HealthService health) {
        this.props = props;
        this.batches = batches;
        this.rules = rules;
        this.detection = detection;
        this.retention = retention;
        this.synthetic = synthetic;
        this.nightly = nightly;
        this.lock = lock;
        this.health = health;
    }

    @Override
    public void run(ApplicationArguments args) {
        String job = props.job();
        if (job.equals("none")) {
            log.info("No job requested (fisre.job=none); schema migration only.");
            return;
        }
        if (job.equals("health")) {
            runHealth();
            return;
        }
        try (JobLock.Held ignored = lock.acquire(job)) {   // one mutating job at a time (REQ-OPS-001)
            runJob(job);
        }
    }

    private void runHealth() {
        java.util.Optional<java.time.LocalDate> date = props.businessDate() == null || props.businessDate().isBlank()
                ? java.util.Optional.empty() : java.util.Optional.of(java.time.LocalDate.parse(props.businessDate()));
        var findings = health.check(date);
        if (findings.isEmpty()) {
            log.info("Health: no findings");
        }
        findings.forEach(f -> log.warn("Health {} {}: {}", f.severity(), f.check(), f.message()));
        long critical = findings.stream().filter(HealthService.Finding::critical).count();
        if (critical > 0) {
            throw new IllegalStateException("Health check found " + critical + " critical problem(s)");
        }
    }

    private void runJob(String job) {
        if (job.equals("load-rules")) {
            rules.load(java.nio.file.Path.of(props.rulesDir()));
            return;
        }
        if (job.equals("promote-loaded")) {
            var results = batches.promoteLoaded();
            long failed = results.stream().filter(r -> r.outcome() == BatchService.Outcome.FAILED).count();
            if (failed > 0) {
                throw new IllegalStateException(failed + " of " + results.size() + " batch(es) failed; see aml.load_batch and aml.load_reject");
            }
            return;
        }
        if (job.equals("nightly")) {
            if (props.batchId() == null || props.batchId().isBlank() || props.businessDate() == null || props.businessDate().isBlank()) {
                throw new IllegalArgumentException("fisre.job=nightly needs fisre.batch-id (FISRE_BATCH_ID) and fisre.business-date (FISRE_BUSINESS_DATE)");
            }
            var steps = nightly.run(props.batchId(), java.time.LocalDate.parse(props.businessDate()));
            var failed = steps.stream().filter(NightlyService.Step::failed).findFirst();
            if (failed.isPresent()) {
                throw new IllegalStateException("Nightly run failed at " + failed.get().step() + ": " + failed.get().message());
            }
            return;
        }
        if (java.util.Set.of("detect", "retain", "generate").contains(job)) {
            if (props.businessDate() == null || props.businessDate().isBlank()) {
                throw new IllegalArgumentException("fisre.job=" + job + " needs fisre.business-date (FISRE_BUSINESS_DATE, YYYY-MM-DD)");
            }
            java.time.LocalDate date = java.time.LocalDate.parse(props.businessDate());
            if (job.equals("retain")) {
                retention.retain(date);
                return;
            }
            if (job.equals("generate")) {
                if (props.batchId() == null || props.batchId().isBlank()) {
                    throw new IllegalArgumentException("fisre.job=generate needs fisre.batch-id (FISRE_BATCH_ID)");
                }
                synthetic.generate(props.batchId(), date);
                return;
            }
            DetectionService.Result r = detection.detect(date);
            if (r.rulesFailed() > 0) {
                throw new IllegalStateException(r.rulesFailed() + " of " + r.rulesRun() + " rule(s) failed; see aml.rule_run");
            }
            return;
        }
        String batchId = props.batchId();
        if (!java.util.Set.of("promote", "clean", "reopen").contains(job)) {
            throw new IllegalArgumentException("Unknown fisre.job '" + job + "' (expected none, health, promote, promote-loaded, clean, reopen, load-rules, detect, retain, generate or nightly)");
        }
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("fisre.job=" + job + " needs fisre.batch-id (FISRE_BATCH_ID)");
        }
        switch (job) {
            case "promote" -> {
                BatchService.Result r = batches.promote(batchId);
                if (r.outcome() == BatchService.Outcome.FAILED) {
                    throw new IllegalStateException("Batch " + batchId + " FAILED: " + r.message());
                }
            }
            case "clean" -> batches.clean(batchId);
            default -> batches.reopen(batchId);
        }
    }
}
