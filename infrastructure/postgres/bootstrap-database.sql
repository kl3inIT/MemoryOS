\set ON_ERROR_STOP on

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT %s',
    :'database_role',
    :'database_password',
    :'connection_limit'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_roles
    WHERE rolname = :'database_role'
)
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT %s',
    :'database_role',
    :'database_password',
    :'connection_limit'
)
\gexec

SELECT format(
    'CREATE DATABASE %I OWNER %I',
    :'database_name',
    :'database_role'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_database
    WHERE datname = :'database_name'
)
\gexec

SELECT format(
    'ALTER DATABASE %I OWNER TO %I',
    :'database_name',
    :'database_role'
)
\gexec

SELECT format(
    'REVOKE CONNECT ON DATABASE %I FROM PUBLIC',
    :'database_name'
)
\gexec

SELECT format(
    'GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I',
    :'database_name',
    :'database_role'
)
\gexec
