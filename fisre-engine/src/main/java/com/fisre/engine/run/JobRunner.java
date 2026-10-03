package com.fisre.engine.run;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.promotion.BatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the job named by fisre.job (FISRE_JOB) for fisre.batch-id (FISRE_BATCH_ID), then the process exits.
 * Any failure, including a batch that fails validation, makes the process exit non-zero.
 */
@Component
public class JobRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final FisreProperties props;
    private final BatchService batches;

    public JobRunner(FisreProperties props, BatchService batches) {
        this.props = props;
        this.batches = batches;
    }

    @Override
    public void run(ApplicationArguments args) {
        String job = props.job();
        if (job.equals("none")) {
            log.info("No job requested (fisre.job=none); schema migration only.");
            return;
        }
        String batchId = props.batchId();
        if (!java.util.Set.of("promote", "clean", "reopen").contains(job)) {
            throw new IllegalArgumentException("Unknown fisre.job '" + job + "' (expected none, promote, clean or reopen)");
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
