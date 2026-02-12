#!/bin/bash
set -e

psql --host "$POSTGRES_HOST" --port "$POSTGRES_PORT" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
DO
\$do\$
BEGIN
    IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'frigate_analyzer_rw') THEN
      RAISE NOTICE 'User "frigate_analyzer_rw" already exists. Skipping.';
    ELSE
      CREATE USER frigate_analyzer_rw PASSWORD 'frigate_analyzer_rw';
      RAISE NOTICE 'User "frigate_analyzer_rw" created.';
    END IF;
END
\$do\$;
EOSQL

isDatabaseExist=`psql --host "$POSTGRES_HOST" --port "$POSTGRES_PORT" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -t -c "SELECT 1 FROM pg_database WHERE datname = 'frigate_analyzer';"`
if [ -z "$isDatabaseExist" ]
then
  psql --host "$POSTGRES_HOST" --port "$POSTGRES_PORT" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c "create database frigate_analyzer owner frigate_analyzer_rw;"
  echo "Database frigate_analyzer create."
else
  echo "Database frigate_analyzer already exists."
fi

psql --host "$POSTGRES_HOST" --port "$POSTGRES_PORT" --username "$POSTGRES_USER" --dbname "frigate_analyzer" <<-EOSQL
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

DO
\$do\$
BEGIN
    IF EXISTS (SELECT FROM information_schema.schemata WHERE schema_name = 'frigate_analyzer') THEN
      RAISE NOTICE 'Schema "frigate_analyzer" already exists. Skipping.';
    ELSE
      CREATE SCHEMA frigate_analyzer AUTHORIZATION frigate_analyzer_rw;
      RAISE NOTICE 'Schema "frigate_analyzer" created.';
    END IF;
END
\$do\$;
EOSQL

