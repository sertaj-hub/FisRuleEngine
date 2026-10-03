package com.fisre.engine.promotion;

import com.fisre.engine.config.FisreProperties;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Drops master transaction partitions older than the retention window (specs/requirements/history.md). */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final Pattern PARTITION = Pattern.compile("txn_(\\d{8})");

    private final NamedParameterJdbcTemplate jdbc;
    private final FisreProperties.Schemas schemas;
    private final FisreProperties.Tuning tuning;

    public RetentionService(NamedParameterJdbcTemplate jdbc, FisreProperties props) {
        this.jdbc = jdbc;
        this.schemas = props.schemas();
        this.tuning = props.tuning();
    }

    /** Keeps partitions whose posting day is on or after (as-of day minus retention months); returns how many were dropped. */
    public int retain(LocalDate businessDate) {
        LocalDate cutoff = businessDate.minusDays(tuning.postingOffsetDays()).minusMonths(tuning.retentionMonths());
        List<String> partitions = jdbc.queryForList("SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " JOIN pg_class p ON p.oid = i.inhparent JOIN pg_namespace n ON n.oid = p.relnamespace"
                + " WHERE n.nspname = :s AND p.relname = 'txn'", Map.of("s", schemas.mst()), String.class);
        int dropped = 0;
        for (String name : partitions) {
            Matcher m = PARTITION.matcher(name);
            if (m.matches() && LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE).isBefore(cutoff)) {
                jdbc.getJdbcOperations().execute("ALTER TABLE " + schemas.mst() + ".txn DETACH PARTITION " + schemas.mst() + "." + name);
                jdbc.getJdbcOperations().execute("DROP TABLE " + schemas.mst() + "." + name);
                dropped++;
            }
        }
        log.info("Retention: kept posting days from {}, dropped {} partition(s)", cutoff, dropped);
        return dropped;
    }
}
