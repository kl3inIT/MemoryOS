#!/bin/sh
set -eu

if [ -n "${MEMORYOS_REDIS_PASSWORD_FILE:-}" ]; then
    test -r "$MEMORYOS_REDIS_PASSWORD_FILE"
    MEMORYOS_REDIS_PASSWORD=$(cat "$MEMORYOS_REDIS_PASSWORD_FILE")
    test -n "$MEMORYOS_REDIS_PASSWORD"
    export MEMORYOS_REDIS_PASSWORD
fi

unset INFISICAL_TOKEN
exec su -p -s /bin/sh memoryos -c 'exec java -jar "$MEMORYOS_APPLICATION_JAR"'
