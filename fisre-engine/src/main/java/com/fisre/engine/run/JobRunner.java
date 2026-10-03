package com.fisre.engine.run;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.detect.DetectionService;
import com.fisre.engine.promotion.BatchService;
import com.fisre.engine.rules.RuleLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the job named by fisre.job (FISRE_JOB), then the process exits. Batch jobs (promote, clean, reopen) need
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

    public JobRunner(FisreProperties props, BatchService batches, RuleLoader rules, DetectionService detection) {
        this.props = props;
        this.batches = batches;
        this.rules = rules;
        this.detection = detection;
    }

    @Override
    public void run(ApplicationArguments args) {
        String job = props.job();
        if (job.equals("none")) {
            log.info("No job requested (fisre.job=none); schema migration only.");
            return;
        }
        if (job.equals("load-rules")) {
            rules.load(java.nio.file.Path.of(props.rulesDir()));
            return;
        }
        if (job.equals("detect")) {
            if (props.businessDate() == null || props.businessDate().isBlank()) {
                throw new IllegalArgumentException("fisre.job=detect needs fisre.business-date (FISRE_BUSINESS_DATE, YYYY-MM-DD)");
            }
            DetectionService.Result r = detection.detect(java.time.LocalDate.parse(props.businessDate()));
            if (r.rulesFailed() > 0) {
                throw new IllegalStateException(r.rulesFailed() + " of " + r.rulesRun() + " rule(s) failed; see aml.rule_run");
            }
            return;
        }
        String batchId = props.batchId();
        if (!java.util.Set.of("promote", "clean", "reopen").contains(job)) {
            throw new IllegalArgumentException("Unknown fisre.job '" + job + "' (expected none, promote, clean, reopen, load-rules or detect)");
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
