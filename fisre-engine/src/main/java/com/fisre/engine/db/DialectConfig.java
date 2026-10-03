package com.fisre.engine.db;

import com.fisre.engine.config.FisreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DialectConfig {

    @Bean
    public Dialect dialect(FisreProperties props) {
        return switch (props.dbVendor().toLowerCase()) {
            case "postgresql" -> new PostgresDialect();
            case "mysql" -> new MySqlDialect();
            case "oracle" -> new OracleDialect();
            default -> throw new IllegalStateException(
                    "Unsupported fisre.db-vendor '" + props.dbVendor() + "' (expected postgresql, mysql or oracle)");
        };
    }
}
