#!/bin/sh
set -eu

bootstrap_file=${INFISICAL_BOOTSTRAP_FILE:-/run/secrets/memoryos_infisical_bootstrap}
test -r "$bootstrap_file"

set -a
# shellcheck disable=SC1090
. "$bootstrap_file"
set +a

: "${INFISICAL_DOMAIN:?INFISICAL_DOMAIN is required}"
: "${INFISICAL_PROJECT_ID:?INFISICAL_PROJECT_ID is required}"
: "${INFISICAL_ENVIRONMENT:?INFISICAL_ENVIRONMENT is required}"
: "${INFISICAL_CLIENT_ID:?INFISICAL_CLIENT_ID is required}"
: "${INFISICAL_CLIENT_SECRET:?INFISICAL_CLIENT_SECRET is required}"

case "$INFISICAL_ENVIRONMENT" in
    dev|staging|prod) ;;
    *)
        printf 'Unsupported INFISICAL_ENVIRONMENT: %s\n' "$INFISICAL_ENVIRONMENT" >&2
        exit 64
        ;;
esac

access_token=$(
    infisical login \
        --domain="$INFISICAL_DOMAIN" \
        --method=universal-auth \
        --client-id="$INFISICAL_CLIENT_ID" \
        --client-secret="$INFISICAL_CLIENT_SECRET" \
        --plain \
        --silent
)
unset INFISICAL_CLIENT_ID INFISICAL_CLIENT_SECRET
INFISICAL_TOKEN=$access_token
export INFISICAL_TOKEN
unset access_token

exec infisical run \
    --projectId="$INFISICAL_PROJECT_ID" \
    --env="$INFISICAL_ENVIRONMENT" \
    -- \
    su -p -s /bin/sh memoryos -c 'unset INFISICAL_TOKEN; exec java -jar application.jar'
