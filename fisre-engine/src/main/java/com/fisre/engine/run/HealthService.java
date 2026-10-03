package com.fisre.engine.run;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.promotion.PartitionCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only operational checks (specs/requirements/hardening.md). Reports only problems; empty means healthy. */
@Service
public class HealthService {

    public record Finding(String severity, String check, String message) {
        public boolean critical() {
            return "CRITICAL".equals(severity);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PartitionCatalog catalog;
    private final String mst;
    private final String aml;
    private final FisreProperties.Tuning tuning;

    public HealthService(NamedParameterJdbcTemplate jdbc, PartitionCatalog catalog, FisreProperties props) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.mst = props.schemas().mst();
        this.aml = props.schemas().aml();
        this.tuning = props.tuning();
    }

    /** @param businessDate when given, also checks that the nightly run for that date completed */
    public List<Finding> check(Optional<LocalDate> businessDate) {
        List<Finding> out = new ArrayList<>();

        for (String b : jdbc.queryForList("SELECT batch_id FROM " + aml + ".load_batch WHERE status = 'PROMOTING'"
                + " AND claimed_ts < CURRENT_TIMESTAMP - make_interval(mins => :m) ORDER BY batch_id",
                Map.of("m", tuning.healthStuckMinutes()), String.class)) {
            out.add(new Finding("CRITICAL", "STUCK_BATCH", "Batch " + b + " has been PROMOTING for over " + tuning.healthStuckMinutes()
                    + " minutes; if no job is running, use the reopen job"));
        }

        for (Map<String, Object> r : jdbc.queryForList("SELECT r.rule_code, r.business_date FROM " + aml + ".rule_run r WHERE r.status = 'FAILED'"
                + " AND r.started_ts > CURRENT_TIMESTAMP - INTERVAL '2 days' AND NOT EXISTS (SELECT 1 FROM " + aml + ".rule_run s"
                + " WHERE s.rule_code = r.rule_code AND s.business_date = r.business_date AND s.status = 'SUCCESS' AND s.run_id > r.run_id)"
                + " GROUP BY r.rule_code, r.business_date ORDER BY 2, 1", Map.of())) {
            out.add(new Finding("CRITICAL", "RULE_FAILED", "Rule " + r.get("rule_code") + " failed for business date " + r.get("business_date")
                    + " and has not succeeded since; its alerts for that date are missing"));
        }

        businessDate.ifPresent(d -> {
            List<Map<String, Object>> steps = jdbc.queryForList("SELECT step, status FROM " + aml + ".nightly_run WHERE business_date = :d ORDER BY run_id",
                    new MapSqlParameterSource("d", java.sql.Date.valueOf(d)));
            String promote = last(steps, "PROMOTE");
            String detect = last(steps, "DETECT");
            boolean promoted = "SUCCESS".equals(promote) || "SKIPPED".equals(promote);
            if (!promoted || !"SUCCESS".equals(detect)) {
                out.add(new Finding("CRITICAL", "NIGHTLY_INCOMPLETE", "Nightly run for " + d + " has not completed (promote: "
                        + (promote == null ? "not run" : promote) + ", detect: " + (detect == null ? "not run" : detect) + ")"));
            }
        });

        Map<String, Object> backlog = jdbc.queryForMap("SELECT COUNT(*) AS n, MIN(created_ts) AS oldest FROM " + aml + ".alert WHERE handed_off_ts IS NULL"
                + " AND created_ts < CURRENT_TIMESTAMP - make_interval(hours => :h)", Map.of("h", tuning.healthAckHours()));
        if (((Number) backlog.get("n")).longValue() > 0) {
            out.add(new Finding("WARN", "ALERT_BACKLOG", backlog.get("n") + " alert(s) older than " + tuning.healthAckHours()
                    + " hours have not been acknowledged by case management (oldest created " + backlog.get("oldest") + ")"));
        }

        var days = catalog.postingDays();
        if (!days.isEmpty()) {
            List<LocalDate> missing = catalog.missingDays(days.first(), days.last());
            if (!missing.isEmpty()) {
                out.add(new Finding("WARN", "MISSING_POSTING_DAYS", missing.size() + " posting day(s) missing between " + days.first() + " and "
                        + days.last() + ": " + missing.stream().limit(10).map(LocalDate::toString).collect(Collectors.joining(", "))
                        + (missing.size() > 10 ? ", ..." : "")));
            }
        }

        for (String t : jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = :s AND table_name LIKE 'txn\\_new\\_%' ORDER BY 1",
                Map.of("s", mst), String.class)) {
            out.add(new Finding("WARN", "LEFTOVER_BUILD_TABLE", mst + "." + t + " is left over from a promotion that did not finish; "
                    + "the reopen job for that batch removes it"));
        }
        return out;
    }

    private static String last(List<Map<String, Object>> steps, String step) {
        String status = null;
        for (Map<String, Object> s : steps) {
            if (step.equals(s.get("step"))) {
                status = (String) s.get("status");
            }
        }
        return status;
    }
}
