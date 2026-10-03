package com.fisre.engine.promotion;

import com.fisre.engine.config.FisreProperties;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Which posting days have a master transaction partition. */
@Component
public class PartitionCatalog {

    private static final Pattern DAY = Pattern.compile("txn_(\\d{8})");

    private final NamedParameterJdbcTemplate jdbc;
    private final String mst;

    public PartitionCatalog(NamedParameterJdbcTemplate jdbc, FisreProperties props) {
        this.jdbc = jdbc;
        this.mst = props.schemas().mst();
    }

    public TreeSet<LocalDate> postingDays() {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (String name : jdbc.queryForList("SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " JOIN pg_class p ON p.oid = i.inhparent JOIN pg_namespace n ON n.oid = p.relnamespace"
                + " WHERE n.nspname = :s AND p.relname = 'txn'", Map.of("s", mst), String.class)) {
            Matcher m = DAY.matcher(name);
            if (m.matches()) {
                days.add(LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE));
            }
        }
        return days;
    }

    /** Days with no partition between the first loaded day (or {@code from}, if later) and {@code to}. */
    public List<LocalDate> missingDays(LocalDate from, LocalDate to) {
        TreeSet<LocalDate> have = postingDays();
        List<LocalDate> missing = new ArrayList<>();
        if (have.isEmpty()) {
            return missing;
        }
        LocalDate start = from.isBefore(have.first()) ? have.first() : from;
        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            if (!have.contains(d)) {
                missing.add(d);
            }
        }
        return missing;
    }
}
