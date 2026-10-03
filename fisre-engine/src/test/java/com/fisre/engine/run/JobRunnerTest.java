package com.fisre.engine.run;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.promotion.BatchService;
import com.fisre.engine.spec.Req;
import org.junit.jupiter.api.Test;

class JobRunnerTest {

    private static JobRunner runner(String job, String batchId) {
        return runner(Fixtures.props(job, batchId, ""), mock(com.fisre.engine.detect.DetectionService.class));
    }

    private static JobRunner runner(FisreProperties props, com.fisre.engine.detect.DetectionService detection) {
        return new JobRunner(props, mock(BatchService.class), mock(com.fisre.engine.rules.RuleLoader.class), detection,
                mock(com.fisre.engine.promotion.RetentionService.class), mock(com.fisre.engine.promotion.SyntheticData.class));
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
    void businessDateJobsNeedADate_andDetectExitsNonZeroWhenARuleFailed() {
        var detection = mock(com.fisre.engine.detect.DetectionService.class);
        for (String job : new String[] {"detect", "retain", "generate"}) {
            assertThatThrownBy(() -> runner(Fixtures.props(job, "B1", ""), detection).run(null)).hasMessageContaining("needs fisre.business-date");
        }
        org.mockito.Mockito.when(detection.detect(java.time.LocalDate.parse("2026-09-30")))
                .thenReturn(new com.fisre.engine.detect.DetectionService.Result(3, 1, 0));
        assertThatThrownBy(() -> runner(Fixtures.props("detect", "", "2026-09-30"), detection).run(null))
                .hasMessageContaining("1 of 3 rule(s) failed");
    }
}
