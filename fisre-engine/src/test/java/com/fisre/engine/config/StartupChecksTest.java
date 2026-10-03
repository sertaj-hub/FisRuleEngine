package com.fisre.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.spec.Req;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupChecksTest {

    @Test
    @Req("REQ-SEC-001")
    void theDefaultPasswordIsRefusedUnlessExplicitlyAllowedForDevelopment() {
        assertThatThrownBy(() -> StartupChecks.validate(new MockEnvironment().withProperty("spring.datasource.password", "fisre")))
                .hasMessageContaining("shipped default").hasMessageContaining("FISRE_ALLOW_DEFAULT_CREDENTIALS");
        assertThatCode(() -> StartupChecks.validate(new MockEnvironment().withProperty("spring.datasource.password", "fisre")
                .withProperty("FISRE_ALLOW_DEFAULT_CREDENTIALS", "true"))).doesNotThrowAnyException();
        assertThatCode(() -> StartupChecks.validate(new MockEnvironment().withProperty("spring.datasource.password", "fisre")
                .withProperty("fisre.allow-default-credentials", "true"))).doesNotThrowAnyException();
        assertThatCode(() -> StartupChecks.validate(new MockEnvironment().withProperty("spring.datasource.password", "S0me-real-secret")))
                .doesNotThrowAnyException();
    }

    @Test
    @Req("REQ-SEC-002")
    void schemaNamesMustBePlainLowercaseIdentifiers() {
        assertThat(new FisreProperties.Schemas("stg", "mst_2", "_aml")).isNotNull();
        for (String bad : new String[] {"stg; DROP TABLE x", "Stg", "1stg", "st-g", "stg.x", "", "a".repeat(64), "stg\"", "stg'"}) {
            assertThatThrownBy(() -> new FisreProperties.Schemas(bad, "mst", "aml")).as(bad).hasMessageContaining("fisre.schemas.stg");
            assertThatThrownBy(() -> new FisreProperties.Schemas("stg", "mst", bad)).as(bad).hasMessageContaining("fisre.schemas.aml");
        }
        assertThatThrownBy(() -> new FisreProperties.Schemas(null, "mst", "aml")).hasMessageContaining("fisre.schemas.stg");
    }

    @Test
    @Req("REQ-SEC-002")
    void theSchemaCheckAlsoRunsBeforeTheContextStarts_becauseFlywayReadsTheNamesFirst() {
        var env = new MockEnvironment().withProperty("spring.datasource.password", "x").withProperty("fisre.schemas.aml", "aml; DROP TABLE x");
        assertThatThrownBy(() -> StartupChecks.validate(env)).hasMessageContaining("fisre.schemas.aml");
        assertThatCode(() -> StartupChecks.validate(new MockEnvironment().withProperty("spring.datasource.password", "x")
                .withProperty("fisre.schemas.stg", "stage_1"))).doesNotThrowAnyException();
    }
}
