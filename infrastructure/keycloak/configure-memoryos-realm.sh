#!/usr/bin/env sh
set -eu

TARGET_REALM=memoryos
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
KCADM=${KCADM:-/opt/keycloak/bin/kcadm.sh}

: "${KEYCLOAK_URL:?KEYCLOAK_URL is required}"
: "${KEYCLOAK_ADMIN_USERNAME:?KEYCLOAK_ADMIN_USERNAME is required}"
: "${KC_CLI_PASSWORD:?KC_CLI_PASSWORD is required}"
: "${MEMORYOS_INITIAL_OWNER_USERNAME:?MEMORYOS_INITIAL_OWNER_USERNAME is required}"
: "${MEMORYOS_BROWSER_CLIENT_SECRET:?MEMORYOS_BROWSER_CLIENT_SECRET is required}"
: "${MEMORYOS_BROWSER_REDIRECT_URI:?MEMORYOS_BROWSER_REDIRECT_URI is required}"

case "$MEMORYOS_BROWSER_REDIRECT_URI" in
    *'*'*)
        echo "MEMORYOS_BROWSER_REDIRECT_URI must not contain a wildcard" >&2
        exit 1
        ;;
    https://*/login/oauth2/code/memoryos | http://127.0.0.1:*/login/oauth2/code/memoryos | http://localhost:*/login/oauth2/code/memoryos)
        ;;
    *)
        echo "MEMORYOS_BROWSER_REDIRECT_URI must be an exact HTTPS callback or loopback development callback" >&2
        exit 1
        ;;
esac


command -v jq >/dev/null 2>&1 || {
    echo "jq is required" >&2
    exit 1
}
umask 077

CONFIG_FILE=$(mktemp)
BROWSER_CLIENT_FILE=$(mktemp)
cleanup() {
    rm -f "$CONFIG_FILE" "$BROWSER_CLIENT_FILE"
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
    --user "$KEYCLOAK_ADMIN_USERNAME" >/dev/null

"$KCADM" get "realms/$TARGET_REALM" --config "$CONFIG_FILE" >/dev/null

find_initial_owner_uuid() {
    rows=$("$KCADM" get users \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -q exact=true \
        -q "username=$MEMORYOS_INITIAL_OWNER_USERNAME" \
        --fields id,username)
    matches=$(printf '%s\n' "$rows" |
        jq -c '[.[] | select(.username == env.MEMORYOS_INITIAL_OWNER_USERNAME)]')
    count=$(printf '%s\n' "$matches" | jq -r 'length')
    if [ "$count" -gt 1 ]; then
        echo "duplicate initial owner username: $MEMORYOS_INITIAL_OWNER_USERNAME" >&2
        exit 1
    fi
    printf '%s\n' "$matches" | jq -r '.[0].id // empty'
}

provision_initial_owner() {
    INITIAL_OWNER_UUID=$(find_initial_owner_uuid)
    if [ -n "$INITIAL_OWNER_UUID" ]; then
        echo "user=$MEMORYOS_INITIAL_OWNER_USERNAME subject=$INITIAL_OWNER_UUID action=existing"
        return
    fi

    : "${MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD:?MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD is required when creating the initial owner}"
    jq -cn '{
        username: env.MEMORYOS_INITIAL_OWNER_USERNAME,
        enabled: true,
        credentials: [{
            type: "password",
            value: env.MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD,
            temporary: true
        }]
    }' |
        "$KCADM" create users \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM" \
            -f - >/dev/null

    INITIAL_OWNER_UUID=$(find_initial_owner_uuid)
    if [ -z "$INITIAL_OWNER_UUID" ]; then
        echo "initial owner creation did not converge" >&2
        exit 1
    fi
    echo "user=$MEMORYOS_INITIAL_OWNER_USERNAME subject=$INITIAL_OWNER_UUID action=created"
}

upsert_client() {
    CLIENT_ID=$1
    CLIENT_FILE=$2
    CLIENT_UUID=$(find_client_uuid)

    if [ -z "$CLIENT_UUID" ]; then
        "$KCADM" create clients \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM" \
            -f "$CLIENT_FILE" >/dev/null
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
            -f "$CLIENT_FILE" >/dev/null
        echo "client=$CLIENT_ID action=updated"
    fi
}

upsert_mapper() {
    MAPPER_NAME=$1
    MAPPER_FILE=$2
    MAPPER_UUID=$(find_mapper_uuid)

    if [ -z "$MAPPER_UUID" ]; then
        "$KCADM" create "clients/$CLIENT_UUID/protocol-mappers/models" \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM" \
            -f "$SCRIPT_DIR/$MAPPER_FILE" >/dev/null
        echo "client=$CLIENT_ID mapper=$MAPPER_NAME action=created"
    else
        "$KCADM" update "clients/$CLIENT_UUID/protocol-mappers/models/$MAPPER_UUID" \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM" \
            -f "$SCRIPT_DIR/$MAPPER_FILE" >/dev/null
        echo "client=$CLIENT_ID mapper=$MAPPER_NAME action=updated"
    fi
}

jq --arg redirectUri "$MEMORYOS_BROWSER_REDIRECT_URI" \
    '.redirectUris = [$redirectUri]' \
    "$SCRIPT_DIR/memoryos-browser-client.json" >"$BROWSER_CLIENT_FILE"

provision_initial_owner
upsert_client memoryos-integration "$SCRIPT_DIR/memoryos-client.json"

upsert_mapper memoryos-api-audience memoryos-audience-mapper.json

upsert_client memoryos-web "$BROWSER_CLIENT_FILE"
jq -cn '{secret: env.MEMORYOS_BROWSER_CLIENT_SECRET}' |
    "$KCADM" update "clients/$CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f - >/dev/null
echo "client=memoryos-web secret=updated"
