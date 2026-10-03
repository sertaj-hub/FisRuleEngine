-- Run once as a DBA. Dev only: SUPERUSER is for convenience; production needs CREATE on the three schemas only.
CREATE ROLE fisre LOGIN PASSWORD 'fisre';
CREATE DATABASE fisre OWNER fisre;
\c fisre
CREATE SCHEMA stg AUTHORIZATION fisre;
CREATE SCHEMA mst AUTHORIZATION fisre;
CREATE SCHEMA aml AUTHORIZATION fisre;
