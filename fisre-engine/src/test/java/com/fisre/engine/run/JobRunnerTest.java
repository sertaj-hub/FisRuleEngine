package com.fisre.engine.run;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.promotion.BatchService;
import com.fisre.engine.spec.Req;
import org.junit.jupiter.api.Test;

class JobRunnerTest {

    private static JobRunner runner(String job, String batchId) {
        var props = new FisreProperties("postgresql", job, batchId, "", "specs/rules", new FisreProperties.Schemas("stg", "mst", "aml"));
        return new JobRunner(props, mock(BatchService.class), mock(com.fisre.engine.rules.RuleLoader.class), mock(com.fisre.engine.detect.DetectionService.class));
    }

    @Test
    @Req("REQ-RUN-002")
    void unknownJobIsRejected() {
        assertThatThrownBy(() -> runner("explode", "B1").run(null)).hasMessageContaining("Unknown fisre.job");
    }

    @Test
    @Req("REQ-RUN-002")
    void batchJobsNeedABatchId() {
        assertThatThrownBy(() -> runner("promote", "").run(null)).hasMessageContaining("needs fisre.batch-id");
    }

    @Test
    @Req("REQ-RUN-002")
    void detectNeedsABusinessDate_andExitsNonZeroWhenARuleFailed() {
        var props = new FisreProperties("postgresql", "detect", "", "", "specs/rules", new FisreProperties.Schemas("stg", "mst", "aml"));
        var detection = mock(com.fisre.engine.detect.DetectionService.class);
        assertThatThrownBy(() -> new JobRunner(props, mock(BatchService.class), mock(com.fisre.engine.rules.RuleLoader.class), detection).run(null))
                .hasMessageContaining("needs fisre.business-date");

        var withDate = new FisreProperties("postgresql", "detect", "", "2026-09-30", "specs/rules", new FisreProperties.Schemas("stg", "mst", "aml"));
        org.mockito.Mockito.when(detection.detect(java.time.LocalDate.parse("2026-09-30")))
                .thenReturn(new com.fisre.engine.detect.DetectionService.Result(3, 1, 0));
        assertThatThrownBy(() -> new JobRunner(withDate, mock(BatchService.class), mock(com.fisre.engine.rules.RuleLoader.class), detection).run(null))
                .hasMessageContaining("1 of 3 rule(s) failed");
    }
}
