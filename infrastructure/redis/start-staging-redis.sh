#!/bin/sh
set -eu

: "${MEMORYOS_REDIS_ADMIN_PASSWORD_FILE:=/run/secrets/redis_admin_password}"
: "${MEMORYOS_REDIS_WORKER_PASSWORD_FILE:=/run/secrets/redis_worker_password}"
: "${MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE:=/run/secrets/redis_inspector_password}"
: "${MEMORYOS_REDIS_TLS_CA_FILE:=/run/secrets/redis_tls_ca}"
: "${MEMORYOS_REDIS_TLS_CERTIFICATE_FILE:=/run/secrets/redis_tls_certificate}"
: "${MEMORYOS_REDIS_TLS_PRIVATE_KEY_FILE:=/run/secrets/redis_tls_private_key}"

ADMIN_PASSWORD=$(cat "$MEMORYOS_REDIS_ADMIN_PASSWORD_FILE")
WORKER_PASSWORD=$(cat "$MEMORYOS_REDIS_WORKER_PASSWORD_FILE")
INSPECTOR_PASSWORD=$(cat "$MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE")
if [ -z "$ADMIN_PASSWORD" ] || [ -z "$WORKER_PASSWORD" ] || [ -z "$INSPECTOR_PASSWORD" ]; then
    echo "Redis administrator, worker, and inspector passwords must be non-empty" >&2
    exit 1
fi

hash_password() {
    printf '%s' "$1" | sha256sum | cut -d' ' -f1
}

ADMIN_HASH=$(hash_password "$ADMIN_PASSWORD")
WORKER_HASH=$(hash_password "$WORKER_PASSWORD")
INSPECTOR_HASH=$(hash_password "$INSPECTOR_PASSWORD")
unset ADMIN_PASSWORD WORKER_PASSWORD INSPECTOR_PASSWORD

umask 077
cp "$MEMORYOS_REDIS_TLS_CA_FILE" /tmp/redis-ca.crt
cp "$MEMORYOS_REDIS_TLS_CERTIFICATE_FILE" /tmp/redis-server.crt
cp "$MEMORYOS_REDIS_TLS_PRIVATE_KEY_FILE" /tmp/redis-server.key
chmod 0400 /tmp/redis-ca.crt /tmp/redis-server.crt /tmp/redis-server.key
cat >/tmp/memoryos-users.acl <<EOF
user default off
user memoryos-admin on #$ADMIN_HASH ~* &* +@all
user memoryos-worker on #$WORKER_HASH ~memoryos:execution:* &* +ping +hello +info +client|setname +client|setinfo +xgroup +xinfo +xadd +xdel +xreadgroup +xack +xpending +xclaim +xautoclaim +xlen +xrange +del +exists
user memoryos-inspector on #$INSPECTOR_HASH ~memoryos:execution:* ~memoryos:cache:* &* +ping +hello +info +dbsize +scan +type +ttl +get +memory +command|info +client|setname +client|setinfo +xinfo +xrange +xlen +xpending
EOF
cat >/tmp/memoryos-redis.conf <<EOF
bind 0.0.0.0
protected-mode yes
port 0
tls-port 6379
tls-cert-file /tmp/redis-server.crt
tls-key-file /tmp/redis-server.key
tls-ca-cert-file /tmp/redis-ca.crt
tls-auth-clients no
aclfile /tmp/memoryos-users.acl
dir /data
appendonly yes
appendfsync everysec
save 300 10
EOF
chown redis:redis /data
chown redis:redis \
    /tmp/memoryos-users.acl \
    /tmp/memoryos-redis.conf \
    /tmp/redis-ca.crt \
    /tmp/redis-server.crt \
    /tmp/redis-server.key

exec setpriv --reuid redis --regid redis --init-groups redis-server /tmp/memoryos-redis.conf
