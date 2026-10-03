package com.fisre.engine.db;

/**
 * The only place where SQL differs between databases. Everything else is portable SQL
 * (see ADR-0002). Add a vendor by implementing this interface and a migration folder.
 */
public interface Dialect {

    String vendor();

    /** Expression for the current timestamp, at the precision of the migration column types. */
    String now();

    /**
     * Insert-or-update every row of {@code source} into {@code target}, matching on the entity key.
     * {@code source} is a SELECT of {@link Entity#columns()} plus {@code batch_id},
     * with unique keys. Named parameters (e.g. :batch) in it are bound by the caller.
     */
    String upsert(String target, Entity entity, String source);
}
