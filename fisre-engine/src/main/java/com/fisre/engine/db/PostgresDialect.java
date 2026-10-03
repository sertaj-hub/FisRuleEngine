package com.fisre.engine.db;

import java.util.stream.Collectors;

public class PostgresDialect implements Dialect {

    @Override
    public String vendor() {
        return "postgresql";
    }

    @Override
    public String now() {
        return "CURRENT_TIMESTAMP";
    }

    @Override
    public String upsert(String target, Entity e, String source) {
        String cols = String.join(", ", e.columns());
        String sets = e.nonKeyColumns().stream().map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "));
        return "INSERT INTO " + target + " (" + cols + ", load_id, updated_ts) "
                + "SELECT " + cols + ", load_id, " + now() + " FROM (" + source + ") src WHERE 1 = 1 "
                + "ON CONFLICT (" + e.keyColumn() + ") DO UPDATE SET " + sets
                + ", load_id = EXCLUDED.load_id, updated_ts = " + now();
    }
}
