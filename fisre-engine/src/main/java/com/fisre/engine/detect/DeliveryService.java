package com.fisre.engine.detect;

import com.fisre.engine.config.FisreProperties;
import java.time.LocalDate;
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
}
