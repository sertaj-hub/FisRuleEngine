package com.fisre.engine.config;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fisre")
public record FisreProperties(String dbVendor, String job, String batchId, String businessDate, String rulesDir,
                              Schemas schemas, Tuning tuning, Bench bench, Confirm confirm) {

    /** Physical names of the three logical schemas. They become SQL identifiers, so they are validated here (REQ-SEC-002). */
    public record Schemas(String stg, String mst, String aml) {
        private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

        public Schemas {
            requireIdentifier("stg", stg);
            requireIdentifier("mst", mst);
            requireIdentifier("aml", aml);
        }

        /** Also called by StartupChecks before the context starts, because Flyway reads these names before they are bound here. */
        public static void requireIdentifier(String logicalName, String value) {
            if (value == null || !IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException("fisre.schemas." + logicalName + " must be a plain lowercase identifier "
                        + "(letters, digits, underscore; at most 63 characters; not starting with a digit), got '" + value + "'");
            }
        }
    }

    /** Scale, safety and behaviour knobs (ADR-0005, ADR-0007). */
    public record Tuning(int postingOffsetDays, int duplicateLookbackDays, int retentionMonths, int maxEvidenceTxns,
                         int detectParallelism, int parallelism, int rejectSampleSize, boolean allowNestedLoops,
                         int ruleTimeoutSeconds, int volumeCheckDays, int volumeCheckMinDays, int volumeLowPercent,
                         int volumeHighPercent, int healthStuckMinutes, int healthAckHours, int healthConfirmHours) {
        public static Tuning defaults() {
            return new Tuning(1, 3, 13, 200, 4, 2, 100, false, 1800, 7, 3, 50, 200, 120, 24, 12);
        }
    }

    /** Numbers received out of band for the confirm-delivery and import-confirmations jobs (REQ-DLV-011, REQ-DLV-012). */
    public record Confirm(Long deliveryId, Integer receivedCount, String receivedChecksum, String reference, String file) {
        public static Confirm defaults() {
            return new Confirm(null, null, null, null, null);
        }
    }

    /** Synthetic data size for the generate job. */
    public record Bench(long txns, long accounts, boolean reference) {
        public static Bench defaults() {
            return new Bench(1000, 100, true);
        }
    }
}
