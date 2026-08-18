#!/usr/bin/env sh
set -eu

TARGET_REALM=memoryos
CLIENT_ID=memoryos-integration
MAPPER_NAME=memoryos-api-audience
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
KCADM=${KCADM:-/opt/keycloak/bin/kcadm.sh}

: "${KEYCLOAK_URL:?KEYCLOAK_URL is required}"
: "${KEYCLOAK_ADMIN_USERNAME:?KEYCLOAK_ADMIN_USERNAME is required}"
: "${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD is required}"


CONFIG_FILE=$(mktemp)
cleanup() {
    rm -f "$CONFIG_FILE"
}
trap cleanup EXIT INT TERM

find_client_uuid() {
    rows=$("$KCADM" get clients \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -q "clientId=$CLIENT_ID" \
        --fields id,clientId \
        --format csv \
        --noquotes \
        | sed '/^$/d')
    count=$(printf '%s\n' "$rows" | sed '/^$/d' | wc -l)
    if [ "$count" -gt 1 ]; then
        echo "duplicate clientId: $CLIENT_ID" >&2
        exit 1
    fi
    printf '%s\n' "$rows" | cut -d, -f1
}

find_mapper_uuid() {
    rows=$("$KCADM" get "clients/$CLIENT_UUID/protocol-mappers/models" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        --fields id,name \
        --format csv \
        --noquotes \
        | grep ",$MAPPER_NAME$" || true)
    count=$(printf '%s\n' "$rows" | sed '/^$/d' | wc -l)
    if [ "$count" -gt 1 ]; then
        echo "duplicate mapper name: $MAPPER_NAME" >&2
        exit 1
    fi
    printf '%s\n' "$rows" | cut -d, -f1
}

"$KCADM" config credentials \
    --config "$CONFIG_FILE" \
    --server "$KEYCLOAK_URL" \
    --realm "$TARGET_REALM" \
    --user "$KEYCLOAK_ADMIN_USERNAME" \
    --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

"$KCADM" get "realms/$TARGET_REALM" --config "$CONFIG_FILE" >/dev/null

CLIENT_UUID=$(find_client_uuid)

if [ -z "$CLIENT_UUID" ]; then
    "$KCADM" create clients \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f "$SCRIPT_DIR/memoryos-client.json" >/dev/null
    CLIENT_UUID=$(find_client_uuid)
    if [ -z "$CLIENT_UUID" ]; then
        echo "client creation did not converge" >&2
        exit 1
    fi
    echo "client=$CLIENT_ID action=created"
else
    "$KCADM" update "clients/$CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f "$SCRIPT_DIR/memoryos-client.json" >/dev/null
    echo "client=$CLIENT_ID action=updated"
fi

MAPPER_UUID=$(find_mapper_uuid)

if [ -z "$MAPPER_UUID" ]; then
    "$KCADM" create "clients/$CLIENT_UUID/protocol-mappers/models" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f "$SCRIPT_DIR/memoryos-audience-mapper.json" >/dev/null
    echo "mapper=$MAPPER_NAME action=created"
else
    "$KCADM" update "clients/$CLIENT_UUID/protocol-mappers/models/$MAPPER_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -s "name=$MAPPER_NAME" \
        -s protocol=openid-connect \
        -s protocolMapper=oidc-audience-mapper \
        -s consentRequired=false \
        -s 'config={"included.custom.audience":"memoryos-api","access.token.claim":"true","id.token.claim":"false","introspection.token.claim":"true"}' >/dev/null
    echo "mapper=$MAPPER_NAME action=updated"
fi
