package com.fisre.engine.run;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * One mutating engine job at a time per database (REQ-OPS-001, ADR-0007). A session-level advisory lock on a
 * dedicated connection: it is released when the job ends, or by the database if the process dies.
 */
@Component
public class JobLock {

    static final long KEY = 7_340_002L;

    private final DataSource dataSource;

    public JobLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Held lock; close it to release. */
    public static final class Held implements AutoCloseable {
        private final Connection connection;

        private Held(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            try (connection) {
                try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, KEY);
                    ps.execute();
                }
            } catch (SQLException e) {
                // closing the connection also releases a session lock; nothing more to do
            }
        }
    }

    public Held acquire(String job) {
        Connection c = null;
        try {
            c = dataSource.getConnection();
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return new Held(c);
                    }
                }
            }
            c.close();
            throw new IllegalStateException("Cannot run job '" + job + "': another engine job holds the database lock. "
                    + "Wait for it to finish (see aml.nightly_run and aml.load_batch) before starting another.");
        } catch (SQLException e) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // already failing
                }
            }
            throw new IllegalStateException("Cannot take the engine job lock: " + e.getMessage(), e);
        }
    }
}
