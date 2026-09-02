#!/bin/sh
set -eu

: "${MEMORYOS_REDIS_HOST:=redis}"
: "${MEMORYOS_REDIS_PORT:=6379}"
: "${MEMORYOS_REDIS_ADMIN_USERNAME:=memoryos-admin}"
: "${MEMORYOS_REDIS_ADMIN_PASSWORD_FILE:=/run/secrets/redis_admin_password}"
: "${MEMORYOS_REDIS_INSPECTOR_USERNAME:=memoryos-inspector}"
: "${MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE:=/run/secrets/redis_inspector_password}"
: "${MEMORYOS_REDIS_TLS_CA_FILE:=/run/secrets/redis_tls_ca}"

ADMIN_PASSWORD=$(cat "$MEMORYOS_REDIS_ADMIN_PASSWORD_FILE")
INSPECTOR_PASSWORD=$(cat "$MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE")
if [ -z "$ADMIN_PASSWORD" ] || [ -z "$INSPECTOR_PASSWORD" ]; then
    echo "Redis administrator and inspector passwords must be non-empty" >&2
    exit 1
fi

export REDISCLI_AUTH=$ADMIN_PASSWORD
redis-cli \
    --tls \
    --cacert "$MEMORYOS_REDIS_TLS_CA_FILE" \
    -h "$MEMORYOS_REDIS_HOST" \
    -p "$MEMORYOS_REDIS_PORT" \
    --user "$MEMORYOS_REDIS_ADMIN_USERNAME" \
    ACL SETUSER "$MEMORYOS_REDIS_INSPECTOR_USERNAME" \
    reset \
    on \
    ">$INSPECTOR_PASSWORD" \
    '~memoryos:execution:*' \
    '~memoryos:cache:*' \
    '+ping' \
    '+hello' \
    '+info' \
    '+dbsize' \
    '+scan' \
    '+type' \
    '+ttl' \
    '+get' \
    '+memory' \
    '+command|info' \
    '+client|setname' \
    '+client|setinfo' \
    '+xinfo' \
    '+xrange' \
    '+xlen' \
    '+xpending' >/dev/null
unset REDISCLI_AUTH ADMIN_PASSWORD INSPECTOR_PASSWORD

echo "Redis inspector ACL reconciled"
