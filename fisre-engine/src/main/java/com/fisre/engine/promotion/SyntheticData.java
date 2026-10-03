package com.fisre.engine.promotion;

import com.fisre.engine.config.FisreProperties;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Generates a synthetic LOADED batch entirely in SQL (no row-by-row Java), for benchmarks and local runs.
 * Accounts are spread over the three products; amounts and types are random but valid.
 */
@Service
public class SyntheticData {

    private static final Logger log = LoggerFactory.getLogger(SyntheticData.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final FisreProperties props;

    public SyntheticData(NamedParameterJdbcTemplate jdbc, FisreProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public void generate(String batchId, LocalDate businessDate) {
        String aml = props.schemas().aml();
        String stg = props.schemas().stg();
        var bench = props.bench();
        LocalDate posting = businessDate.minusDays(props.tuning().postingOffsetDays());
        jdbc.update("INSERT INTO " + aml + ".load_batch (batch_id, business_date, status) VALUES (:b, :d, 'LOADING')",
                new MapSqlParameterSource().addValue("b", batchId).addValue("d", java.sql.Date.valueOf(businessDate)));
        long seq = jdbc.queryForObject("SELECT batch_seq FROM " + aml + ".load_batch WHERE batch_id = :b", Map.of("b", batchId), Long.class);
        var p = new MapSqlParameterSource().addValue("b", batchId).addValue("accounts", bench.accounts())
                .addValue("customers", Math.max(1, bench.accounts() * 2 / 3)).addValue("txns", bench.txns())
                .addValue("posting", java.sql.Date.valueOf(posting));
        if (bench.reference()) {
            jdbc.update("INSERT INTO " + stg + ".customer_b" + seq + " (batch_id, customer_id, customer_type, full_name, country_code, status)"
                    + " SELECT :b, 'CUST' || g, 'INDIVIDUAL', 'Customer ' || g, 'US', 'ACTIVE' FROM generate_series(1, :customers) g", p);
            jdbc.update("INSERT INTO " + stg + ".account_b" + seq + " (batch_id, account_id, primary_customer_id, product_type, status, open_date, currency)"
                    + " SELECT :b, 'ACC' || g, 'CUST' || (1 + g % :customers), (ARRAY['DEPOSIT', 'CARD', 'LOAN'])[1 + g % 3], 'ACTIVE',"
                    + " DATE '2020-01-01' + CAST(g % 1000 AS integer), 'USD' FROM generate_series(1, :accounts) g", p);
        }
        // Random values are drawn in the select list of the row source so every row gets its own (a lateral sub-select
        // that does not reference the row would be evaluated once). Types follow the account's product (n % 3 as above).
        jdbc.update("INSERT INTO " + stg + ".txn_b" + seq + " (batch_id, transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency)"
                + " SELECT :b, :b || '-' || v.g, 'ACC' || v.n, CAST(:posting AS timestamp) + v.r4 * 86399 * INTERVAL '1 second', :posting,"
                + " CASE v.n % 3"
                + "   WHEN 0 THEN (ARRAY['CASH_DEPOSIT', 'CASH_WITHDRAWAL', 'ACH_CREDIT', 'ACH_DEBIT', 'WIRE_IN', 'WIRE_OUT'])[1 + floor(v.r1 * 6)::int]"
                + "   WHEN 1 THEN (ARRAY['POS_PURCHASE', 'ECOM_PURCHASE', 'CARD_PAYMENT', 'CARD_CASH_ADVANCE'])[1 + floor(v.r1 * 4)::int]"
                + "   ELSE (ARRAY['LOAN_PAYMENT', 'LOAN_PAYMENT', 'LOAN_PAYOFF'])[1 + floor(v.r1 * 3)::int] END,"
                + " CASE WHEN v.r2 < 0.5 THEN 'CREDIT' ELSE 'DEBIT' END,"
                + " ROUND(CAST(5 + v.r3 * v.r3 * 12000 AS numeric), 2), 'USD'"
                + " FROM (SELECT g, 1 + floor(random() * :accounts)::bigint AS n, random() AS r1, random() AS r2, random() AS r3, random() AS r4"
                + " FROM generate_series(1, :txns) g) v", p);
        jdbc.update("UPDATE " + aml + ".load_batch SET status = 'LOADED', loaded_ts = CURRENT_TIMESTAMP WHERE batch_id = :b", p);
        log.info("Generated batch {} (business date {}, posting day {}): {} transactions, {} accounts{}", batchId, businessDate, posting,
                bench.txns(), bench.accounts(), bench.reference() ? " with customers and accounts" : "");
    }
}
