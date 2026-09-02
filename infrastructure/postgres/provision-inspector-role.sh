#!/bin/sh
set -eu

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${MEMORYOS_DATABASE_NAME:=memoryos}"
: "${MEMORYOS_DATABASE_USERNAME:=memoryos_app}"
: "${MEMORYOS_PGWEB_USERNAME:=memoryos_pgweb}"
: "${MEMORYOS_PGWEB_PASSWORD_FILE:=/run/secrets/pgweb_database_password}"
: "${MEMORYOS_PGWEB_PGPASS_PATH:=/pgpass/.pgpass}"
: "${MEMORYOS_PGWEB_UID:=1000}"
: "${MEMORYOS_PGWEB_GID:=1000}"

PGWEB_PASSWORD=$(cat "$MEMORYOS_PGWEB_PASSWORD_FILE")
case "$PGWEB_PASSWORD" in
    '' | *:* | *\\* | *' '* | *'	'* | *'
'*)
        echo "pgweb database password must be non-empty and contain no colons, backslashes, whitespace, or newlines" >&2
        exit 1
        ;;
esac

psql \
    --no-psqlrc \
    --set ON_ERROR_STOP=1 \
    --set database_name="$MEMORYOS_DATABASE_NAME" \
    --set application_role="$MEMORYOS_DATABASE_USERNAME" \
    --set inspector_role="$MEMORYOS_PGWEB_USERNAME" \
    --set inspector_password="$PGWEB_PASSWORD" \
    postgres <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 5',
    :'inspector_role',
    :'inspector_password'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'inspector_role'
)
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 5',
    :'inspector_role',
    :'inspector_password'
)
\gexec
SELECT format('ALTER ROLE %I IN DATABASE %I SET default_transaction_read_only = on', :'inspector_role', :'database_name')
\gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM %I', :'database_name', :'inspector_role')
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'database_name', :'inspector_role')
\gexec
SQL

psql \
    --no-psqlrc \
    --set ON_ERROR_STOP=1 \
    --set application_role="$MEMORYOS_DATABASE_USERNAME" \
    --set inspector_role="$MEMORYOS_PGWEB_USERNAME" \
    "$MEMORYOS_DATABASE_NAME" <<'SQL'
SELECT format('REVOKE ALL ON SCHEMA public FROM %I', :'inspector_role')
\gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'inspector_role')
\gexec
SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', :'inspector_role')
\gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA public TO %I', :'inspector_role')
\gexec
SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', :'inspector_role')
\gexec
SELECT format('GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'inspector_role')
\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON TABLES TO %I', :'application_role', :'inspector_role')
\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON SEQUENCES TO %I', :'application_role', :'inspector_role')
\gexec
SQL

umask 077
mkdir -p "$(dirname "$MEMORYOS_PGWEB_PGPASS_PATH")"
rm -f "$MEMORYOS_PGWEB_PGPASS_PATH"
printf '%s:%s:%s:%s:%s\n' \
    "$PGHOST" \
    5432 \
    "$MEMORYOS_DATABASE_NAME" \
    "$MEMORYOS_PGWEB_USERNAME" \
    "$PGWEB_PASSWORD" >"$MEMORYOS_PGWEB_PGPASS_PATH"
chmod 0600 "$MEMORYOS_PGWEB_PGPASS_PATH"
chown "$MEMORYOS_PGWEB_UID:$MEMORYOS_PGWEB_GID" "$MEMORYOS_PGWEB_PGPASS_PATH"

echo "postgres inspector role and passfile reconciled"
