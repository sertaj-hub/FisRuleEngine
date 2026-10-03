package com.fisre.engine.run;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.promotion.BatchService;
import com.fisre.engine.spec.Req;
import org.junit.jupiter.api.Test;

class JobRunnerTest {

    private static JobRunner runner(String job, String batchId) {
        var props = new FisreProperties("postgresql", job, batchId, new FisreProperties.Schemas("stg", "mst", "aml"));
        return new JobRunner(props, mock(BatchService.class));
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
}
