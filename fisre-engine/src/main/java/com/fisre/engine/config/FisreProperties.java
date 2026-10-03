package com.fisre.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fisre")
public record FisreProperties(String dbVendor, String job, Schemas schemas) {

    /** Physical names of the three logical schemas (schema on PostgreSQL, database on MySQL, user on Oracle). */
    public record Schemas(String stg, String mst, String aml) {}
}
