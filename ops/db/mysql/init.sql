-- Run once as a DBA. In MySQL a "schema" is a database.
CREATE DATABASE IF NOT EXISTS stg;
CREATE DATABASE IF NOT EXISTS mst;
CREATE DATABASE IF NOT EXISTS aml;
CREATE USER IF NOT EXISTS 'fisre'@'%' IDENTIFIED BY 'fisre';
GRANT ALL ON stg.* TO 'fisre'@'%';
GRANT ALL ON mst.* TO 'fisre'@'%';
GRANT ALL ON aml.* TO 'fisre'@'%';
