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
        return new DialectConfig().dialect(new FisreProperties(vendor, "none", "", "", "specs/rules", new FisreProperties.Schemas("stg", "mst", "aml")));
    }

    @Test
    @Req("REQ-CFG-002")
    void postgresqlIsSupported() {
        assertThat(dialectFor("PostgreSQL").vendor()).isEqualTo("postgresql");
        assertThat(dialectFor("postgresql").upsert("m.customer", Entity.CUSTOMER, "SELECT 1")).contains("ON CONFLICT (customer_id)");
    }

    @Test
    @Req("REQ-CFG-002")
    void unsupportedVendorFailsFast() {
        assertThatThrownBy(() -> dialectFor("db2")).hasMessageContaining("Unsupported fisre.db-vendor");
    }
}
