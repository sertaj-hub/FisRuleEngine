package com.fisre.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fisre")
public record FisreProperties(String dbVendor, String job, String batchId, String businessDate, String rulesDir, Schemas schemas) {

    /** Physical names of the three logical schemas. */
    public record Schemas(String stg, String mst, String aml) {}
}
