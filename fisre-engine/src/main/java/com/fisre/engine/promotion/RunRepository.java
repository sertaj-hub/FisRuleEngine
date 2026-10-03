package com.fisre.engine.promotion;

import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.db.Dialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RunRepository {

    private final JdbcTemplate jdbc;
    private final Dialect dialect;
    private final String run;
    private final String runEntity;

    public RunRepository(JdbcTemplate jdbc, Dialect dialect, FisreProperties props) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.run = props.schemas().aml() + ".batch_run";
        this.runEntity = props.schemas().aml() + ".batch_run_entity";
    }

    public void start(String runId, String job) {
        jdbc.update("INSERT INTO " + run + " (run_id, job_name, status, started_ts) VALUES (?, ?, 'RUNNING', "
                + dialect.now() + ")", runId, job);
    }

    public void recordEntity(String runId, PromotionService.EntityResult r) {
        jdbc.update("INSERT INTO " + runEntity + " (run_id, entity, rejected_cnt, promoted_cnt) VALUES (?, ?, ?, ?)",
                runId, r.entity(), r.rejected(), r.promoted());
    }

    public void finish(String runId, String status, String error) {
        String msg = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        jdbc.update("UPDATE " + run + " SET status = ?, ended_ts = " + dialect.now() + ", error_msg = ? WHERE run_id = ?",
                status, msg, runId);
    }
}
