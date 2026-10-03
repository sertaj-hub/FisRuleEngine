package com.fisre.engine.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.db.Dialect;
import com.fisre.engine.db.DialectConfig;
import com.fisre.engine.db.Entity;
import com.fisre.engine.spec.Req;
import org.junit.jupiter.api.Test;

class DialectSelectionTest {

    private static Dialect dialectFor(String vendor) {
        return new DialectConfig().dialect(new FisreProperties(vendor, "none", new FisreProperties.Schemas("stg", "mst", "aml")));
    }

    @Test
    @Req("REQ-CFG-001")
    void vendorIsChosenByConfigurationOnly() {
        assertThat(dialectFor("postgresql").vendor()).isEqualTo("postgresql");
        assertThat(dialectFor("MySQL").vendor()).isEqualTo("mysql");
        assertThat(dialectFor("oracle").vendor()).isEqualTo("oracle");
    }

    @Test
    @Req("REQ-CFG-002")
    void unsupportedVendorFailsFast() {
        assertThatThrownBy(() -> dialectFor("db2")).hasMessageContaining("Unsupported fisre.db-vendor");
    }

    @Test
    @Req("REQ-CFG-001")
    void upsertSqlIsGeneratedPerVendor() {
        String src = "SELECT 1";
        assertThat(dialectFor("postgresql").upsert("m.customer", Entity.CUSTOMER, src)).contains("ON CONFLICT (customer_id)");
        assertThat(dialectFor("mysql").upsert("m.customer", Entity.CUSTOMER, src)).contains("ON DUPLICATE KEY UPDATE");
        assertThat(dialectFor("oracle").upsert("m.customer", Entity.CUSTOMER, src)).startsWith("MERGE INTO m.customer");
    }
}
