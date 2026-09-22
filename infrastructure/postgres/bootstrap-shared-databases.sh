#!/bin/sh
set -eu

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${MEMORYOS_DATABASE_NAME:=memoryos}"
: "${MEMORYOS_DATABASE_USERNAME:=memoryos_app}"
: "${MEMORYOS_DATABASE_PASSWORD:?MEMORYOS_DATABASE_PASSWORD is required}"
: "${MEMORYOS_KEYCLOAK_DATABASE_NAME:=keycloak}"
: "${MEMORYOS_KEYCLOAK_DATABASE_USERNAME:=keycloak}"
: "${MEMORYOS_KEYCLOAK_DATABASE_PASSWORD:?MEMORYOS_KEYCLOAK_DATABASE_PASSWORD is required}"
# Above the pools the api and the worker hold together, so that a migration, an operator looking
# at data during an incident, and the overlap while a rollout replaces a container each still have
# a connection to use. A role at its limit reports "too many connections", never "raise the limit".
: "${MEMORYOS_DATABASE_CONNECTION_LIMIT:=40}"
# Twice what Keycloak's own pool holds at most (KC_DB_POOL_MAX_SIZE), for the same reason.
: "${MEMORYOS_KEYCLOAK_DATABASE_CONNECTION_LIMIT:=20}"

bootstrap_database() {
    database_name="$1"
    database_role="$2"
    database_password="$3"
    connection_limit="$4"

    psql \
        --no-psqlrc \
        --set ON_ERROR_STOP=1 \
        --set database_name="${database_name}" \
        --set database_role="${database_role}" \
        --set database_password="${database_password}" \
        --set connection_limit="${connection_limit}" \
        --file /bootstrap/bootstrap-database.sql \
        postgres
}

bootstrap_database \
    "${MEMORYOS_DATABASE_NAME}" \
    "${MEMORYOS_DATABASE_USERNAME}" \
    "${MEMORYOS_DATABASE_PASSWORD}" \
    "${MEMORYOS_DATABASE_CONNECTION_LIMIT}"

bootstrap_database \
    "${MEMORYOS_KEYCLOAK_DATABASE_NAME}" \
    "${MEMORYOS_KEYCLOAK_DATABASE_USERNAME}" \
    "${MEMORYOS_KEYCLOAK_DATABASE_PASSWORD}" \
    "${MEMORYOS_KEYCLOAK_DATABASE_CONNECTION_LIMIT}"

psql \
    --no-psqlrc \
    --set ON_ERROR_STOP=1 \
    --set memoryos_database_name="${MEMORYOS_DATABASE_NAME}" \
    --set keycloak_database_name="${MEMORYOS_KEYCLOAK_DATABASE_NAME}" \
    --tuples-only \
    --no-align \
    postgres <<'SQL'
SELECT datname
FROM pg_database
WHERE datname IN (:'memoryos_database_name', :'keycloak_database_name')
ORDER BY datname;
SQL
