#!/usr/bin/env sh
set -eu

OUTPUT_DIRECTORY=${1:-/apps/memoryos/secrets/mailpit}
DATA_DIRECTORY=${MEMORYOS_MAILPIT_DATA_DIRECTORY:-/apps/memoryos/mailpit}
MAILPIT_UID=${MEMORYOS_MAILPIT_UID:-1000}
MAILPIT_GID=${MEMORYOS_MAILPIT_GID:-1000}
SMTP_USERNAME=${MEMORYOS_MAILPIT_SMTP_USERNAME:-memoryos-keycloak}

case "$SMTP_USERNAME" in
    '' | *[!A-Za-z0-9._-]*)
        echo "MEMORYOS_MAILPIT_SMTP_USERNAME must contain only letters, digits, '.', '_' or '-'" >&2
        exit 1
        ;;
esac
case "$MAILPIT_UID:$MAILPIT_GID" in
    *[!0-9:]* | :* | *:)
        echo "MEMORYOS_MAILPIT_UID and MEMORYOS_MAILPIT_GID must be positive integers" >&2
        exit 1
        ;;
esac
if [ "$MAILPIT_UID" -eq 0 ] || [ "$MAILPIT_GID" -eq 0 ]; then
    echo "MEMORYOS_MAILPIT_UID and MEMORYOS_MAILPIT_GID must be positive integers" >&2
    exit 1
fi

umask 077
mkdir -p "$OUTPUT_DIRECTORY" "$DATA_DIRECTORY"
CURRENT_UID=$(id -u)
if [ "$CURRENT_UID" -eq 0 ]; then
    chown "$MAILPIT_UID:$MAILPIT_GID" "$DATA_DIRECTORY"
elif [ "$CURRENT_UID" -ne "$MAILPIT_UID" ]; then
    echo "run as root or as configured Mailpit UID $MAILPIT_UID to provision $DATA_DIRECTORY" >&2
    exit 1
fi
chmod 0700 "$OUTPUT_DIRECTORY" "$DATA_DIRECTORY"

CA_CERTIFICATE="$OUTPUT_DIRECTORY/ca.crt"
CA_PRIVATE_KEY="$OUTPUT_DIRECTORY/ca.key"
SMTP_CERTIFICATE="$OUTPUT_DIRECTORY/smtp.crt"
SMTP_PRIVATE_KEY="$OUTPUT_DIRECTORY/smtp.key"
SMTP_AUTH_FILE="$OUTPUT_DIRECTORY/smtp-auth.txt"

existing_count=0
for path in "$CA_CERTIFICATE" "$CA_PRIVATE_KEY" "$SMTP_CERTIFICATE" "$SMTP_PRIVATE_KEY" "$SMTP_AUTH_FILE"; do
    if [ -e "$path" ]; then
        existing_count=$((existing_count + 1))
    fi
done

if [ "$existing_count" -eq 5 ]; then
    openssl verify -CAfile "$CA_CERTIFICATE" "$SMTP_CERTIFICATE" >/dev/null
    openssl x509 -in "$SMTP_CERTIFICATE" -noout -checkend 86400 >/dev/null
    awk -F: -v username="$SMTP_USERNAME" 'NR == 1 && $1 == username { matched = 1 } END { exit !(NR == 1 && matched) }' "$SMTP_AUTH_FILE"
    echo "mailpit staging secrets already provisioned"
    openssl x509 -in "$SMTP_CERTIFICATE" -noout -fingerprint -sha256
    exit 0
fi

if [ "$existing_count" -ne 0 ]; then
    echo "refusing to replace an incomplete Mailpit secret set in $OUTPUT_DIRECTORY" >&2
    exit 1
fi

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
    -subj "/CN=MemoryOS staging Mailpit CA" \
    -out "$TEMPORARY_DIRECTORY/ca.crt" >/dev/null

openssl genpkey \
    -algorithm EC \
    -pkeyopt ec_paramgen_curve:P-256 \
    -out "$TEMPORARY_DIRECTORY/smtp.key" >/dev/null
openssl req \
    -new \
    -sha256 \
    -key "$TEMPORARY_DIRECTORY/smtp.key" \
    -subj "/CN=mailpit" \
    -out "$TEMPORARY_DIRECTORY/smtp.csr" >/dev/null
cat >"$TEMPORARY_DIRECTORY/smtp-extensions.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:mailpit,DNS:memoryos-mailpit
EOF
openssl x509 \
    -req \
    -sha256 \
    -days 397 \
    -in "$TEMPORARY_DIRECTORY/smtp.csr" \
    -CA "$TEMPORARY_DIRECTORY/ca.crt" \
    -CAkey "$TEMPORARY_DIRECTORY/ca.key" \
    -CAcreateserial \
    -extfile "$TEMPORARY_DIRECTORY/smtp-extensions.cnf" \
    -out "$TEMPORARY_DIRECTORY/smtp.crt" >/dev/null

if ! SMTP_PASSWORD=$(openssl rand -base64 48); then
    echo "failed to generate Mailpit SMTP password" >&2
    exit 1
fi
if [ -z "$SMTP_PASSWORD" ]; then
    echo "generated Mailpit SMTP password is empty" >&2
    exit 1
fi
printf '%s:%s\n' "$SMTP_USERNAME" "$SMTP_PASSWORD" >"$TEMPORARY_DIRECTORY/smtp-auth.txt"
unset SMTP_PASSWORD

install -m 0600 "$TEMPORARY_DIRECTORY/ca.crt" "$CA_CERTIFICATE"
install -m 0600 "$TEMPORARY_DIRECTORY/ca.key" "$CA_PRIVATE_KEY"
install -m 0600 "$TEMPORARY_DIRECTORY/smtp.crt" "$SMTP_CERTIFICATE"
install -m 0600 "$TEMPORARY_DIRECTORY/smtp.key" "$SMTP_PRIVATE_KEY"
install -m 0600 "$TEMPORARY_DIRECTORY/smtp-auth.txt" "$SMTP_AUTH_FILE"

openssl verify -CAfile "$CA_CERTIFICATE" "$SMTP_CERTIFICATE" >/dev/null
echo "mailpit staging secrets provisioned"
openssl x509 -in "$SMTP_CERTIFICATE" -noout -fingerprint -sha256
