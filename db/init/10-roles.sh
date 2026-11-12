#!/usr/bin/env bash
# Runs once inside the postgres container, as the superuser, when the data volume is new.
#   seatwave_owner  Module 13's owner role: owns the schema seatwave, where customers lives, and
#                   may read the index statistics the study prints (pgstattuple's pgstatindex)
# The password comes from a Compose secret, as a psql variable, never pasted into SQL text.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_password="$(< /run/secrets/owner_password)" <<'SQL'
CREATE ROLE seatwave_owner LOGIN PASSWORD :'owner_password';
GRANT CONNECT ON DATABASE seatwave TO seatwave_owner;
REVOKE CONNECT ON DATABASE seatwave FROM PUBLIC;
CREATE SCHEMA seatwave AUTHORIZATION seatwave_owner;
ALTER ROLE seatwave_owner SET search_path = seatwave, public;
-- pgstatindex reports an index's levels, pages and leaf fill; only the superuser may create the
-- extension, and members of pg_stat_scan_tables may call it.
CREATE EXTENSION pgstattuple;
GRANT pg_stat_scan_tables TO seatwave_owner;
SQL
