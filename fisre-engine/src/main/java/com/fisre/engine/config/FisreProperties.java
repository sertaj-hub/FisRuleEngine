package com.fisre.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fisre")
public record FisreProperties(String dbVendor, String job, String batchId, String businessDate, String rulesDir,
                              Schemas schemas, Tuning tuning, Bench bench) {

    /** Physical names of the three logical schemas. */
    public record Schemas(String stg, String mst, String aml) {}

    /** Scale and behaviour knobs (ADR-0005). */
    public record Tuning(int postingOffsetDays, int duplicateLookbackDays, int retentionMonths, int maxEvidenceTxns,
                         int detectParallelism, int parallelism, int rejectSampleSize) {
        public static Tuning defaults() {
            return new Tuning(1, 3, 13, 200, 4, 2, 100);
        }
    }

    /** Synthetic data size for the generate job. */
    public record Bench(long txns, long accounts, boolean reference) {
        public static Bench defaults() {
            return new Bench(1000, 100, true);
        }
    }
}
