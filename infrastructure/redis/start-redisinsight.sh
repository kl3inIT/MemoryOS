#!/bin/sh
set -eu

: "${MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE:=/run/secrets/redis_inspector_password}"
: "${MEMORYOS_REDISINSIGHT_ENCRYPTION_KEY_FILE:=/run/secrets/redisinsight_encryption_key}"

RI_REDIS_PASSWORD=$(cat "$MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE")
RI_ENCRYPTION_KEY=$(cat "$MEMORYOS_REDISINSIGHT_ENCRYPTION_KEY_FILE")
if [ -z "$RI_REDIS_PASSWORD" ] || [ -z "$RI_ENCRYPTION_KEY" ]; then
    echo "Redis Insight password and encryption key must be non-empty" >&2
    exit 1
fi
export RI_REDIS_PASSWORD RI_ENCRYPTION_KEY

exec su node -s /bin/sh -c \
    'exec /usr/src/app/docker-entry.sh node redisinsight/api/dist/src/main'
