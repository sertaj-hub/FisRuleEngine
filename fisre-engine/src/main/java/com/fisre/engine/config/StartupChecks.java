package com.fisre.engine.config;

import org.springframework.core.env.Environment;

/** Checks that run before the application context starts, so a bad setup never touches the database (REQ-SEC-001). */
public final class StartupChecks {

    static final String DEFAULT_PASSWORD = "fisre";

    private StartupChecks() {}

    public static void validate(Environment env) {
        // Flyway builds DDL from these names before the configuration is bound, so check them here (REQ-SEC-002).
        FisreProperties.Schemas.requireIdentifier("stg", env.getProperty("fisre.schemas.stg", "stg"));
        FisreProperties.Schemas.requireIdentifier("mst", env.getProperty("fisre.schemas.mst", "mst"));
        FisreProperties.Schemas.requireIdentifier("aml", env.getProperty("fisre.schemas.aml", "aml"));
        String password = env.getProperty("spring.datasource.password", "");
        boolean allowed = Boolean.parseBoolean(env.getProperty("FISRE_ALLOW_DEFAULT_CREDENTIALS", "false"))
                || Boolean.parseBoolean(env.getProperty("fisre.allow-default-credentials", "false"));
        if (DEFAULT_PASSWORD.equals(password) && !allowed) {
            throw new IllegalStateException("Refusing to start: the database password is the shipped default. Set FISRE_DB_PASSWORD, "
                    + "or FISRE_ALLOW_DEFAULT_CREDENTIALS=true for local development only.");
        }
    }
}
