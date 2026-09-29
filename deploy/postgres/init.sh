#!/usr/bin/env bash
# Runs once, when the Postgres volume is first created. Each service gets its own
# login role that owns exactly one schema and has no rights anywhere else, so
# "one service, one schema" is enforced by the database, not by convention.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE fulfillops FROM PUBLIC;
SQL

for svc in order inventory payment fulfilment; do
  var="$(echo "$svc" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  password="${!var:?$var must be set}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
       -v role="${svc}_svc" -v password="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'password';
GRANT CONNECT ON DATABASE fulfillops TO :"role";
CREATE SCHEMA :"role" AUTHORIZATION :"role";
SQL
done
