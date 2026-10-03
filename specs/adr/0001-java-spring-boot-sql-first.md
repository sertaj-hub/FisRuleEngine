# ADR-0001: Java 21 + Spring Boot, SQL-first engine

Status: accepted

Decision: build the engine in Java 21 with Spring Boot. Heavy work is set-based SQL run by the database;
the application orchestrates, validates and records runs. No row-by-row processing in the JVM.

Why: 10M transactions per day fits set-based SQL; JDBC and Flyway give the best multi-database support;
a single jar suits on-prem operations; matches the team's skills. Python is reserved for later ML scoring.

Consequences: performance work is SQL and index tuning per vendor, not JVM tuning.
