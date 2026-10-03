package com.fisre.engine.db;

import java.util.stream.Collectors;

public class OracleDialect implements Dialect {

    @Override
    public String vendor() {
        return "oracle";
    }

    @Override
    public String now() {
        return "SYSTIMESTAMP";
    }

    @Override
    public String upsert(String target, Entity e, String source) {
        String cols = String.join(", ", e.columns());
        String sets = e.nonKeyColumns().stream().map(c -> "t." + c + " = src." + c).collect(Collectors.joining(", "));
        String srcCols = e.columns().stream().map(c -> "src." + c).collect(Collectors.joining(", "));
        return "MERGE INTO " + target + " t USING (" + source + ") src ON (t." + e.keyColumn() + " = src." + e.keyColumn() + ") "
                + "WHEN MATCHED THEN UPDATE SET " + sets + ", t.load_id = src.load_id, t.updated_ts = " + now() + " "
                + "WHEN NOT MATCHED THEN INSERT (" + cols + ", load_id, updated_ts) "
                + "VALUES (" + srcCols + ", src.load_id, " + now() + ")";
    }
}
