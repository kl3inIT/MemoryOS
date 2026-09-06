#!/usr/bin/env sh
set -eu

INSPECTION_DIRECTORY=${MEMORYOS_INSPECTION_SECRET_DIRECTORY:-/apps/memoryos/secrets/inspection}
REDIS_DIRECTORY=${MEMORYOS_REDIS_SECRET_DIRECTORY:-/apps/memoryos/secrets/redis}
ROTATE_TLS=${MEMORYOS_REDIS_TLS_ROTATE:-false}
case "$ROTATE_TLS" in
    true | false)
        ;;
    *)
        echo "MEMORYOS_REDIS_TLS_ROTATE must be true or false" >&2
        exit 1
        ;;
esac
umask 077
mkdir -p "$INSPECTION_DIRECTORY" "$REDIS_DIRECTORY"
chmod 0700 "$INSPECTION_DIRECTORY" "$REDIS_DIRECTORY"

create_hex_secret() {
    path=$1
    bytes=$2
    if [ ! -e "$path" ]; then
        openssl rand -hex "$bytes" >"$path"
        chmod 0600 "$path"
    fi
    test -s "$path"
}

create_base64_secret() {
    path=$1
    bytes=$2
    if [ ! -e "$path" ]; then
        openssl rand -base64 "$bytes" | tr -d '\n' >"$path"
        chmod 0600 "$path"
    fi
    test -s "$path"
}

create_hex_secret "$INSPECTION_DIRECTORY/pgweb-database-password.txt" 32
create_base64_secret "$INSPECTION_DIRECTORY/pgweb-oauth2-client-secret.txt" 48
create_hex_secret "$INSPECTION_DIRECTORY/pgweb-oauth2-cookie-secret.txt" 16
create_base64_secret "$INSPECTION_DIRECTORY/redisinsight-oauth2-client-secret.txt" 48
create_hex_secret "$INSPECTION_DIRECTORY/redisinsight-oauth2-cookie-secret.txt" 16
create_hex_secret "$INSPECTION_DIRECTORY/redisinsight-encryption-key.txt" 32
create_base64_secret "$INSPECTION_DIRECTORY/minio-console-oidc-client-secret.txt" 48

create_hex_secret "$REDIS_DIRECTORY/admin-password.txt" 32
create_hex_secret "$REDIS_DIRECTORY/worker-password.txt" 32
create_hex_secret "$REDIS_DIRECTORY/inspector-password.txt" 32

TLS_COUNT=0
for path in \
    "$REDIS_DIRECTORY/ca.crt" \
    "$REDIS_DIRECTORY/ca.key" \
    "$REDIS_DIRECTORY/server.crt" \
    "$REDIS_DIRECTORY/server.key"; do
    if [ -e "$path" ]; then
        TLS_COUNT=$((TLS_COUNT + 1))
    fi
done

if [ "$TLS_COUNT" -eq 0 ] || [ "$ROTATE_TLS" = true ]; then
    TEMPORARY_DIRECTORY=$(mktemp -d)
    cleanup() {
        rm -rf "$TEMPORARY_DIRECTORY"
    }
    trap cleanup EXIT INT TERM

    openssl genpkey \
        -algorithm EC \
        -pkeyopt ec_paramgen_curve:P-256 \
        -out "$TEMPORARY_DIRECTORY/ca.key" >/dev/null
    openssl req \
        -x509 \
        -new \
        -sha256 \
        -days 825 \
        -key "$TEMPORARY_DIRECTORY/ca.key" \
        -subj "/CN=MemoryOS staging Redis CA" \
        -out "$TEMPORARY_DIRECTORY/ca.crt" >/dev/null

    openssl genpkey \
        -algorithm EC \
        -pkeyopt ec_paramgen_curve:P-256 \
        -out "$TEMPORARY_DIRECTORY/server.key" >/dev/null
    openssl req \
        -new \
        -sha256 \
        -key "$TEMPORARY_DIRECTORY/server.key" \
        -subj "/CN=redis" \
        -out "$TEMPORARY_DIRECTORY/server.csr" >/dev/null
    cat >"$TEMPORARY_DIRECTORY/server-extensions.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:redis,DNS:memoryos-redis
EOF
    openssl x509 \
        -req \
        -sha256 \
        -days 397 \
        -in "$TEMPORARY_DIRECTORY/server.csr" \
        -CA "$TEMPORARY_DIRECTORY/ca.crt" \
        -CAkey "$TEMPORARY_DIRECTORY/ca.key" \
        -CAcreateserial \
        -extfile "$TEMPORARY_DIRECTORY/server-extensions.cnf" \
        -out "$TEMPORARY_DIRECTORY/server.crt" >/dev/null

    install -m 0600 "$TEMPORARY_DIRECTORY/ca.crt" "$REDIS_DIRECTORY/ca.crt"
    install -m 0600 "$TEMPORARY_DIRECTORY/ca.key" "$REDIS_DIRECTORY/ca.key"
    install -m 0600 "$TEMPORARY_DIRECTORY/server.crt" "$REDIS_DIRECTORY/server.crt"
    install -m 0600 "$TEMPORARY_DIRECTORY/server.key" "$REDIS_DIRECTORY/server.key"
elif [ "$TLS_COUNT" -ne 4 ]; then
    echo "refusing to replace an incomplete Redis TLS secret set in $REDIS_DIRECTORY" >&2
    exit 1
elif ! openssl x509 -in "$REDIS_DIRECTORY/server.crt" -noout -checkend 2592000 >/dev/null; then
    echo "Redis TLS certificate expires within 30 days; rerun with MEMORYOS_REDIS_TLS_ROTATE=true and redeploy all Redis clients" >&2
    exit 1
fi

openssl verify -CAfile "$REDIS_DIRECTORY/ca.crt" "$REDIS_DIRECTORY/server.crt" >/dev/null
openssl x509 -in "$REDIS_DIRECTORY/server.crt" -noout -checkend 86400 >/dev/null

echo "inspection staging secrets ready"
echo "mount generated values only through declared Compose secrets; load OAuth client values only into controlled Keycloak reconciliation"
