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
        return runner(props, detection, nightly, mock(HealthService.class));
    }

    private static JobRunner runner(FisreProperties props, com.fisre.engine.detect.DetectionService detection, NightlyService nightly, HealthService health) {
        return new JobRunner(props, mock(BatchService.class), mock(com.fisre.engine.rules.RuleLoader.class), detection,
                mock(com.fisre.engine.promotion.RetentionService.class), mock(com.fisre.engine.promotion.SyntheticData.class), nightly,
                mock(JobLock.class), health, mock(com.fisre.engine.detect.DeliveryService.class));
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

    @Test
    @Req("REQ-HLT-002")
    void healthExitsNonZeroOnlyForCriticalFindings() {
        var health = mock(HealthService.class);
        var detection = mock(com.fisre.engine.detect.DetectionService.class);
        var props = Fixtures.props("health", "", "2026-10-01");
        org.mockito.Mockito.when(health.check(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(
                new HealthService.Finding("WARN", "ALERT_BACKLOG", "3 alerts waiting")));
        org.assertj.core.api.Assertions.assertThatCode(() -> runner(props, detection, mock(NightlyService.class), health).run(null)).doesNotThrowAnyException();

        org.mockito.Mockito.when(health.check(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(
                new HealthService.Finding("WARN", "ALERT_BACKLOG", "3 alerts waiting"),
                new HealthService.Finding("CRITICAL", "STUCK_BATCH", "stuck")));
        assertThatThrownBy(() -> runner(props, detection, mock(NightlyService.class), health).run(null)).hasMessageContaining("1 critical problem");
    }

    @Test
    @Req({"REQ-DLV-011", "REQ-DLV-012"})
    void outOfBandConfirmationJobsNeedTheirInputs_andFailWhenTheNumbersDoNotReconcile() {
        var detection = mock(com.fisre.engine.detect.DetectionService.class);
        assertThatThrownBy(() -> runner(Fixtures.props("confirm-delivery", "", ""), detection).run(null)).hasMessageContaining("FISRE_DELIVERY_ID");
        assertThatThrownBy(() -> runner(Fixtures.props("import-confirmations", "", ""), detection).run(null)).hasMessageContaining("FISRE_CONFIRM_FILE");
    }

    @Test
    @Req("REQ-REJ-004")
    void resolveRejectionNeedsTheNoticeTheActionAndANote() {
        var detection = mock(com.fisre.engine.detect.DetectionService.class);
        assertThatThrownBy(() -> runner(Fixtures.props("resolve-rejection", "", ""), detection).run(null))
                .hasMessageContaining("FISRE_REJECTION_ID").hasMessageContaining("FISRE_RESOLUTION_NOTE");
    }
}
