package com.fisre.engine.run;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.promotion.PromotionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runs the job named by fisre.job (FISRE_JOB), then the process exits. A failure exits non-zero. */
@Component
public class JobRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final FisreProperties props;
    private final PromotionService promotion;

    public JobRunner(FisreProperties props, PromotionService promotion) {
        this.props = props;
        this.promotion = promotion;
    }

    @Override
    public void run(ApplicationArguments args) {
        switch (props.job()) {
            case "none" -> log.info("No job requested (fisre.job=none); schema migration only.");
            case "promote" -> promotion.promote();
            default -> throw new IllegalArgumentException("Unknown fisre.job '" + props.job() + "' (expected none or promote)");
        }
    }
}
