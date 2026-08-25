#!/usr/bin/env sh
set -eu

OUTPUT_DIRECTORY=${1:-/apps/memoryos/secrets/mailpit}
DATA_DIRECTORY=${MEMORYOS_MAILPIT_DATA_DIRECTORY:-/apps/memoryos/mailpit}
MAILPIT_UID=${MEMORYOS_MAILPIT_UID:-1000}
MAILPIT_GID=${MEMORYOS_MAILPIT_GID:-1000}
SMTP_USERNAME=${MEMORYOS_MAILPIT_SMTP_USERNAME:-memoryos-keycloak}
: "${MEMORYOS_MAILPIT_ALLOWED_EMAIL:?MEMORYOS_MAILPIT_ALLOWED_EMAIL is required}"

case "$SMTP_USERNAME" in
    '' | *[!A-Za-z0-9._-]*)
        echo "MEMORYOS_MAILPIT_SMTP_USERNAME must contain only letters, digits, '.', '_' or '-'" >&2
        exit 1
        ;;
esac
case "$MEMORYOS_MAILPIT_ALLOWED_EMAIL" in
    *@*)
        ;;
    *)
        echo "MEMORYOS_MAILPIT_ALLOWED_EMAIL must be an email address" >&2
        exit 1
        ;;
esac
case "$MEMORYOS_MAILPIT_ALLOWED_EMAIL" in
    *' '* | *'	'* | *:*)
        echo "MEMORYOS_MAILPIT_ALLOWED_EMAIL must not contain whitespace or ':'" >&2
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
OAUTH2_CLIENT_SECRET_FILE="$OUTPUT_DIRECTORY/oauth2-client-secret.txt"
OAUTH2_COOKIE_SECRET_FILE="$OUTPUT_DIRECTORY/oauth2-cookie-secret.txt"
OAUTH2_ALLOWED_EMAILS_FILE="$OUTPUT_DIRECTORY/oauth2-allowed-emails.txt"

count_existing() {
    count=0
    for path do
        if [ -e "$path" ]; then
            count=$((count + 1))
        fi
    done
    printf '%s' "$count"
}

TEMPORARY_DIRECTORY=$(mktemp -d)
cleanup() {
    rm -rf "$TEMPORARY_DIRECTORY"
}
trap cleanup EXIT INT TERM

smtp_count=$(count_existing \
    "$CA_CERTIFICATE" \
    "$CA_PRIVATE_KEY" \
    "$SMTP_CERTIFICATE" \
    "$SMTP_PRIVATE_KEY" \
    "$SMTP_AUTH_FILE")
if [ "$smtp_count" -eq 0 ]; then
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
elif [ "$smtp_count" -ne 5 ]; then
    echo "refusing to replace an incomplete Mailpit SMTP secret set in $OUTPUT_DIRECTORY" >&2
    exit 1
fi

openssl verify -CAfile "$CA_CERTIFICATE" "$SMTP_CERTIFICATE" >/dev/null
openssl x509 -in "$SMTP_CERTIFICATE" -noout -checkend 86400 >/dev/null
awk -F: -v username="$SMTP_USERNAME" 'NR == 1 && $1 == username { matched = 1 } END { exit !(NR == 1 && matched) }' "$SMTP_AUTH_FILE"

oauth2_count=$(count_existing \
    "$OAUTH2_CLIENT_SECRET_FILE" \
    "$OAUTH2_COOKIE_SECRET_FILE" \
    "$OAUTH2_ALLOWED_EMAILS_FILE")
if [ "$oauth2_count" -eq 0 ]; then
    if ! OAUTH2_CLIENT_SECRET=$(openssl rand -base64 48); then
        echo "failed to generate Mailpit OAuth2 client secret" >&2
        exit 1
    fi
    if ! OAUTH2_COOKIE_SECRET=$(openssl rand -hex 16); then
        echo "failed to generate Mailpit OAuth2 cookie secret" >&2
        exit 1
    fi
    if [ -z "$OAUTH2_CLIENT_SECRET" ] || [ -z "$OAUTH2_COOKIE_SECRET" ]; then
        echo "generated Mailpit OAuth2 secret is empty" >&2
        exit 1
    fi
    printf '%s' "$OAUTH2_CLIENT_SECRET" >"$TEMPORARY_DIRECTORY/oauth2-client-secret.txt"
    printf '%s' "$OAUTH2_COOKIE_SECRET" >"$TEMPORARY_DIRECTORY/oauth2-cookie-secret.txt"
    printf '%s\n' "$MEMORYOS_MAILPIT_ALLOWED_EMAIL" >"$TEMPORARY_DIRECTORY/oauth2-allowed-emails.txt"
    unset OAUTH2_CLIENT_SECRET OAUTH2_COOKIE_SECRET

    install -m 0600 "$TEMPORARY_DIRECTORY/oauth2-client-secret.txt" "$OAUTH2_CLIENT_SECRET_FILE"
    install -m 0600 "$TEMPORARY_DIRECTORY/oauth2-cookie-secret.txt" "$OAUTH2_COOKIE_SECRET_FILE"
    install -m 0600 "$TEMPORARY_DIRECTORY/oauth2-allowed-emails.txt" "$OAUTH2_ALLOWED_EMAILS_FILE"
elif [ "$oauth2_count" -ne 3 ]; then
    echo "refusing to replace an incomplete Mailpit OAuth2 secret set in $OUTPUT_DIRECTORY" >&2
    exit 1
fi

client_secret_size=$(wc -c <"$OAUTH2_CLIENT_SECRET_FILE")
if [ "$client_secret_size" -ne 64 ]; then
    echo "Mailpit OAuth2 client secret must contain exactly 64 bytes" >&2
    exit 1
fi
cookie_secret_size=$(wc -c <"$OAUTH2_COOKIE_SECRET_FILE")
if [ "$cookie_secret_size" -ne 32 ]; then
    echo "Mailpit OAuth2 cookie secret must contain exactly 32 bytes" >&2
    exit 1
fi
awk -v email="$MEMORYOS_MAILPIT_ALLOWED_EMAIL" 'NR == 1 && $0 == email { matched = 1 } END { exit !(NR == 1 && matched) }' "$OAUTH2_ALLOWED_EMAILS_FILE"

echo "mailpit staging secrets ready"
openssl x509 -in "$SMTP_CERTIFICATE" -noout -fingerprint -sha256
