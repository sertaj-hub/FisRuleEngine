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
        return runner(props, detection, mock(NightlyService.class));
    }

    private static JobRunner runner(FisreProperties props, com.fisre.engine.detect.DetectionService detection, NightlyService nightly) {
        return new JobRunner(props, mock(BatchService.class), mock(com.fisre.engine.rules.RuleLoader.class), detection,
                mock(com.fisre.engine.promotion.RetentionService.class), mock(com.fisre.engine.promotion.SyntheticData.class), nightly);
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

    @Test
    @Req({"REQ-RUN-002", "REQ-RUN-003"})
    void nightlyNeedsBatchAndDate_andExitsNonZeroAtTheFailingStep() {
        var detection = mock(com.fisre.engine.detect.DetectionService.class);
        assertThatThrownBy(() -> runner(Fixtures.props("nightly", "B1", ""), detection).run(null)).hasMessageContaining("needs fisre.batch-id");
        var nightly = mock(NightlyService.class);
        org.mockito.Mockito.when(nightly.run("B1", java.time.LocalDate.parse("2026-10-01"))).thenReturn(java.util.List.of(
                new NightlyService.Step("PROMOTE", "SUCCESS", null), new NightlyService.Step("DETECT", "FAILED", "2 of 7 rule(s) failed"),
                new NightlyService.Step("RETAIN", "SKIPPED", "an earlier step failed")));
        assertThatThrownBy(() -> runner(Fixtures.props("nightly", "B1", "2026-10-01"), detection, nightly).run(null))
                .hasMessageContaining("failed at DETECT").hasMessageContaining("2 of 7");
    }
}
