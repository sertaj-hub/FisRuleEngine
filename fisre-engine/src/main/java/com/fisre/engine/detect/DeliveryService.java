package com.fisre.engine.detect;

import com.fisre.engine.config.FisreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds and publishes the delivery of one business date's alerts to case management (specs/requirements/delivery.md,
 * ADR-0008). A delivery is OPEN (invisible) while rules run and READY once published with its control totals.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    public record Published(long deliveryId, int revision, int alertCount) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String aml;

    public DeliveryService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, FisreProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.aml = props.schemas().aml();
    }

    /**
     * The delivery that this run's alerts go into: the date's OPEN delivery if there is one (a re-run after a failure),
     * otherwise a new revision (the first one, or the next after a published one).
     */
    public long openFor(LocalDate date) {
        return tx.execute(s -> {
            Map<String, Object> p = Map.of("d", java.sql.Date.valueOf(date));
            var open = jdbc.queryForList("SELECT delivery_id FROM " + aml + ".alert_delivery WHERE business_date = :d AND status = 'OPEN'", p, Long.class);
            if (!open.isEmpty()) {
                return open.get(0);
            }
            return jdbc.queryForObject("INSERT INTO " + aml + ".alert_delivery (business_date, revision, status) VALUES (:d,"
                    + " COALESCE((SELECT MAX(revision) FROM " + aml + ".alert_delivery WHERE business_date = :d), 0) + 1, 'OPEN') RETURNING delivery_id", p, Long.class);
        });
    }

    /**
     * Publishes an OPEN delivery: writes the control totals and makes it visible. A later revision with no alerts
     * (a re-run that added nothing) is removed instead, so retries leave no empty revisions.
     */
    public Optional<Published> publish(long deliveryId) {
        return tx.execute(s -> {
            Map<String, Object> p = Map.of("id", deliveryId);
            Map<String, Object> d = jdbc.queryForMap("SELECT revision, (SELECT COUNT(*) FROM " + aml + ".alert WHERE delivery_id = :id) AS n FROM "
                    + aml + ".alert_delivery WHERE delivery_id = :id AND status = 'OPEN'", p);
            int revision = ((Number) d.get("revision")).intValue();
            int count = ((Number) d.get("n")).intValue();
            if (revision > 1 && count == 0) {
                jdbc.update("DELETE FROM " + aml + ".alert_delivery WHERE delivery_id = :id", p);
                log.info("Delivery {} (revision {}) had nothing new; not published", deliveryId, revision);
                return Optional.empty();
            }
            jdbc.update("UPDATE " + aml + ".alert_delivery SET status = 'READY', ready_ts = CURRENT_TIMESTAMP, alert_count = :n,"
                    + " rule_counts = (SELECT COALESCE(jsonb_object_agg(rule_code, c), '{}'::jsonb) FROM (SELECT rule_code, COUNT(*) AS c FROM " + aml
                    + ".alert WHERE delivery_id = :id GROUP BY rule_code) x),"
                    + " checksum = " + aml + ".compute_alert_checksum(ARRAY(SELECT alert_id FROM " + aml + ".alert WHERE delivery_id = :id))"
                    + " WHERE delivery_id = :id AND status = 'OPEN'", Map.of("id", deliveryId, "n", count));
            // Only a hint for consumers that LISTEN; delivered when this transaction commits. Polling is always enough.
            jdbc.queryForObject("SELECT pg_notify('aml_delivery_ready', json_build_object('delivery_id', d.delivery_id, 'business_date', d.business_date,"
                    + " 'revision', d.revision, 'alert_count', d.alert_count)::text)::text FROM " + aml + ".alert_delivery d WHERE d.delivery_id = :id", p, String.class);
            log.info("Delivery {} (revision {}) published with {} alert(s)", deliveryId, revision, count);
            return Optional.of(new Published(deliveryId, revision, count));
        });
    }

    /** The latest delivery of a business date, as "id, status, alerts", for logs and the nightly record. */
    public Optional<String> describeLatest(LocalDate date) {
        var rows = jdbc.queryForList("SELECT 'delivery ' || delivery_id || ' revision ' || revision || ' ' || status || ', ' || COALESCE(alert_count, 0) || ' alert(s)'"
                + " FROM " + aml + ".alert_delivery WHERE business_date = :d ORDER BY revision DESC",
                Map.of("d", java.sql.Date.valueOf(date)), String.class);
        return rows.stream().findFirst();
    }

    /** Result of one out-of-band confirmation. */
    public record ConfirmationResult(String line, String outcome, String message) {
        public boolean ok() {
            return "CONFIRMED".equals(outcome);
        }
    }

    /**
     * Applies numbers received outside the database (a message, a ticket, a file) with the same comparison as
     * aml.confirm_delivery, and records the channel and a reference for the audit trail (REQ-DLV-011).
     */
    public String confirmOutOfBand(long deliveryId, int receivedCount, String receivedChecksum, String channel, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("A reference (ticket, e-mail or message id) is required for an out-of-band confirmation");
        }
        return tx.execute(s -> {
            Map<String, Object> p = Map.of("id", deliveryId);
            String before = jdbc.queryForList("SELECT status FROM " + aml + ".alert_delivery WHERE delivery_id = :id", p, String.class).stream().findFirst().orElse(null);
            String result = jdbc.queryForObject("SELECT " + aml + ".confirm_delivery(:id, :c, :s)", Map.of("id", deliveryId, "c", receivedCount, "s", receivedChecksum), String.class);
            if ("CONFIRMED".equals(result) && !"CONFIRMED".equals(before)) {
                jdbc.update("UPDATE " + aml + ".alert_delivery SET confirmation_channel = :ch, confirmation_reference = :ref WHERE delivery_id = :id",
                        Map.of("id", deliveryId, "ch", channel, "ref", reference.substring(0, Math.min(reference.length(), 200))));
            }
            log.info("Delivery {} out-of-band confirmation via {} ({}): {}", deliveryId, channel, reference, result);
            return result;
        });
    }

    /**
     * Reads a CSV of confirmations: delivery_id,received_count,received_checksum[,reference]; an optional header line
     * and blank lines are ignored. Every line is attempted; one bad line does not stop the others (REQ-DLV-012).
     */
    public List<ConfirmationResult> importConfirmations(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read confirmation file " + file + ": " + e.getMessage(), e);
        }
        List<ConfirmationResult> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.toLowerCase().startsWith("delivery_id")) {
                continue;
            }
            try {
                String[] f = line.split(",", -1);
                if (f.length < 3) {
                    throw new IllegalArgumentException("expected delivery_id,received_count,received_checksum[,reference]");
                }
                String reference = f.length > 3 && !f[3].isBlank() ? f[3].trim() : file.getFileName().toString();
                String result = confirmOutOfBand(Long.parseLong(f[0].trim()), Integer.parseInt(f[1].trim()), f[2].trim(), "FILE", reference);
                out.add(new ConfirmationResult(line, result, null));
            } catch (RuntimeException e) {
                out.add(new ConfirmationResult(line, "ERROR", e.getMessage()));
            }
        }
        return out;
    }
}
