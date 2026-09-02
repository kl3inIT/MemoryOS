#!/bin/sh
set -eu

if [ -n "${MEMORYOS_REDIS_PASSWORD_FILE:-}" ]; then
    test -r "$MEMORYOS_REDIS_PASSWORD_FILE"
    MEMORYOS_REDIS_PASSWORD=$(cat "$MEMORYOS_REDIS_PASSWORD_FILE")
    test -n "$MEMORYOS_REDIS_PASSWORD"
    export MEMORYOS_REDIS_PASSWORD
fi

if [ -n "${MEMORYOS_REDIS_TLS_CA_FILE:-}" ]; then
    test -r "$MEMORYOS_REDIS_TLS_CA_FILE"
    staged_ca=/tmp/memoryos-redis-ca.crt
    cp "$MEMORYOS_REDIS_TLS_CA_FILE" "$staged_ca"
    chmod 0444 "$staged_ca"
    MEMORYOS_REDIS_TLS_CA_CERTIFICATE=file:$staged_ca
    export MEMORYOS_REDIS_TLS_CA_CERTIFICATE
fi

unset INFISICAL_TOKEN
exec su -p -s /bin/sh memoryos -c 'exec java -jar "$MEMORYOS_APPLICATION_JAR"'
