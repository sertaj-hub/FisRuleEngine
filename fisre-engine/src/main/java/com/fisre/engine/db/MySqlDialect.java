package com.fisre.engine.db;

import java.util.stream.Collectors;

public class MySqlDialect implements Dialect {

    @Override
    public String vendor() {
        return "mysql";
    }

    @Override
    public String now() {
        return "CURRENT_TIMESTAMP(6)";
    }

    @Override
    public String upsert(String target, Entity e, String source) {
        String cols = String.join(", ", e.columns());
        String sets = e.nonKeyColumns().stream().map(c -> c + " = VALUES(" + c + ")").collect(Collectors.joining(", "));
        return "INSERT INTO " + target + " (" + cols + ", load_id, updated_ts) "
                + "SELECT " + cols + ", load_id, " + now() + " FROM (" + source + ") src "
                + "ON DUPLICATE KEY UPDATE " + sets + ", load_id = VALUES(load_id), updated_ts = " + now();
    }
}
