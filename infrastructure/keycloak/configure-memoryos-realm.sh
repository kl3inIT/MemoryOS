#!/usr/bin/env sh
set -eu

TARGET_REALM=memoryos
KEYCLOAK_ADMIN_REALM=${KEYCLOAK_ADMIN_REALM:-master}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
KCADM=${KCADM:-/opt/keycloak/bin/kcadm.sh}

: "${KEYCLOAK_URL:?KEYCLOAK_URL is required}"
: "${KEYCLOAK_ADMIN_USERNAME:?KEYCLOAK_ADMIN_USERNAME is required}"
: "${KC_CLI_PASSWORD:?KC_CLI_PASSWORD is required}"
: "${MEMORYOS_INITIAL_OWNER_USERNAME:?MEMORYOS_INITIAL_OWNER_USERNAME is required}"
: "${MEMORYOS_INITIAL_OWNER_EMAIL:?MEMORYOS_INITIAL_OWNER_EMAIL is required}"
: "${MEMORYOS_BROWSER_CLIENT_SECRET:?MEMORYOS_BROWSER_CLIENT_SECRET is required}"
: "${MEMORYOS_BROWSER_REDIRECT_URI:?MEMORYOS_BROWSER_REDIRECT_URI is required}"
: "${MEMORYOS_MAILPIT_PUBLIC_URL:?MEMORYOS_MAILPIT_PUBLIC_URL is required}"
: "${MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET:?MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET is required}"
: "${MEMORYOS_KEYCLOAK_SMTP_HOST:?MEMORYOS_KEYCLOAK_SMTP_HOST is required}"
: "${MEMORYOS_KEYCLOAK_SMTP_FROM:?MEMORYOS_KEYCLOAK_SMTP_FROM is required}"
MEMORYOS_KEYCLOAK_SMTP_PORT=${MEMORYOS_KEYCLOAK_SMTP_PORT:-587}
MEMORYOS_KEYCLOAK_SMTP_AUTH=${MEMORYOS_KEYCLOAK_SMTP_AUTH:-true}
MEMORYOS_KEYCLOAK_SMTP_STARTTLS=${MEMORYOS_KEYCLOAK_SMTP_STARTTLS:-true}
MEMORYOS_KEYCLOAK_SMTP_SSL=${MEMORYOS_KEYCLOAK_SMTP_SSL:-false}
MEMORYOS_KEYCLOAK_SMTP_FROM_DISPLAY_NAME=${MEMORYOS_KEYCLOAK_SMTP_FROM_DISPLAY_NAME:-MemoryOS}
MEMORYOS_KEYCLOAK_SMTP_REPLY_TO=${MEMORYOS_KEYCLOAK_SMTP_REPLY_TO:-$MEMORYOS_KEYCLOAK_SMTP_FROM}
MEMORYOS_KEYCLOAK_SMTP_ENVELOPE_FROM=${MEMORYOS_KEYCLOAK_SMTP_ENVELOPE_FROM:-$MEMORYOS_KEYCLOAK_SMTP_FROM}

case "$MEMORYOS_KEYCLOAK_SMTP_AUTH" in
    true)
        : "${MEMORYOS_KEYCLOAK_SMTP_USERNAME:?MEMORYOS_KEYCLOAK_SMTP_USERNAME is required when SMTP auth is enabled}"
        : "${MEMORYOS_KEYCLOAK_SMTP_PASSWORD:?MEMORYOS_KEYCLOAK_SMTP_PASSWORD is required when SMTP auth is enabled}"
        ;;
    false)
        ;;
    *)
        echo "MEMORYOS_KEYCLOAK_SMTP_AUTH must be true or false" >&2
        exit 1
        ;;
esac

case "$MEMORYOS_KEYCLOAK_SMTP_STARTTLS:$MEMORYOS_KEYCLOAK_SMTP_SSL" in
    true:false | false:true)
        ;;
    *)
        echo "exactly one of MEMORYOS_KEYCLOAK_SMTP_STARTTLS or MEMORYOS_KEYCLOAK_SMTP_SSL must be true" >&2
        exit 1
        ;;
esac

case "$MEMORYOS_KEYCLOAK_SMTP_PORT" in
    '' | *[!0-9]*)
        echo "MEMORYOS_KEYCLOAK_SMTP_PORT must be numeric" >&2
        exit 1
        ;;
esac

export MEMORYOS_KEYCLOAK_SMTP_HOST
export MEMORYOS_KEYCLOAK_SMTP_FROM
export MEMORYOS_KEYCLOAK_SMTP_USERNAME
export MEMORYOS_KEYCLOAK_SMTP_PASSWORD
export MEMORYOS_KEYCLOAK_SMTP_PORT
export MEMORYOS_KEYCLOAK_SMTP_AUTH
export MEMORYOS_KEYCLOAK_SMTP_STARTTLS
export MEMORYOS_KEYCLOAK_SMTP_SSL
export MEMORYOS_KEYCLOAK_SMTP_FROM_DISPLAY_NAME
export MEMORYOS_KEYCLOAK_SMTP_REPLY_TO
export MEMORYOS_KEYCLOAK_SMTP_ENVELOPE_FROM

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
MEMORYOS_BROWSER_PUBLIC_URL=${MEMORYOS_BROWSER_REDIRECT_URI%/login/oauth2/code/memoryos}

case "$MEMORYOS_MAILPIT_PUBLIC_URL" in
    https://*.nip.io)
        ;;
    *)
        echo "MEMORYOS_MAILPIT_PUBLIC_URL must be an exact HTTPS nip.io origin" >&2
        exit 1
        ;;
esac



command -v jq >/dev/null 2>&1 || {
    echo "jq is required" >&2
    exit 1
}
umask 077
export MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET

CONFIG_FILE=$(mktemp)
BROWSER_CLIENT_FILE=$(mktemp)
MAILPIT_CLIENT_FILE=$(mktemp)
cleanup() {
    rm -f "$CONFIG_FILE" "$BROWSER_CLIENT_FILE" "$MAILPIT_CLIENT_FILE"
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
    --realm "$KEYCLOAK_ADMIN_REALM" \
    --user "$KEYCLOAK_ADMIN_USERNAME" >/dev/null

"$KCADM" get "realms/$TARGET_REALM" --config "$CONFIG_FILE" >/dev/null

configure_self_registration() {
    jq -cn '{
        registrationAllowed: true,
        registrationEmailAsUsername: true,
        loginWithEmailAllowed: true,
        duplicateEmailsAllowed: false,
        verifyEmail: true,
        smtpServer: ({
            host: env.MEMORYOS_KEYCLOAK_SMTP_HOST,
            port: env.MEMORYOS_KEYCLOAK_SMTP_PORT,
            from: env.MEMORYOS_KEYCLOAK_SMTP_FROM,
            fromDisplayName: env.MEMORYOS_KEYCLOAK_SMTP_FROM_DISPLAY_NAME,
            replyTo: env.MEMORYOS_KEYCLOAK_SMTP_REPLY_TO,
            envelopeFrom: env.MEMORYOS_KEYCLOAK_SMTP_ENVELOPE_FROM,
            auth: env.MEMORYOS_KEYCLOAK_SMTP_AUTH,
            ssl: env.MEMORYOS_KEYCLOAK_SMTP_SSL,
            starttls: env.MEMORYOS_KEYCLOAK_SMTP_STARTTLS
        } + if env.MEMORYOS_KEYCLOAK_SMTP_AUTH == "true" then {
            authType: "basic",
            user: env.MEMORYOS_KEYCLOAK_SMTP_USERNAME,
            password: env.MEMORYOS_KEYCLOAK_SMTP_PASSWORD
        } else {} end)
    }' |
        "$KCADM" update "realms/$TARGET_REALM" \
            --config "$CONFIG_FILE" \
            -f - >/dev/null
    echo "realm=$TARGET_REALM self-registration=enabled email-verification=required smtp=updated"
}

configure_self_registration

find_initial_owner_uuid() {
    if [ -n "${MEMORYOS_INITIAL_OWNER_SUBJECT:-}" ]; then
        row=$("$KCADM" get "users/$MEMORYOS_INITIAL_OWNER_SUBJECT" \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM" \
            --fields id,username)
        matches=$(printf '%s\n' "$row" |
            jq -c '[select(.id == env.MEMORYOS_INITIAL_OWNER_SUBJECT and .username == env.MEMORYOS_INITIAL_OWNER_USERNAME)]')
        count=$(printf '%s\n' "$matches" | jq -r 'length')
        if [ "$count" -ne 1 ]; then
            echo "configured initial owner subject does not match the expected username" >&2
            exit 1
        fi
        printf '%s\n' "$matches" | jq -r '.[0].id'
        return
    fi

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
    action=existing
    if [ -z "$INITIAL_OWNER_UUID" ]; then
        : "${MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD:?MEMORYOS_INITIAL_OWNER_TEMPORARY_PASSWORD is required when creating the initial owner}"
        jq -cn '{
            username: env.MEMORYOS_INITIAL_OWNER_USERNAME,
            email: env.MEMORYOS_INITIAL_OWNER_EMAIL,
            emailVerified: true,
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
        action=created
    fi

    jq -cn '{
        email: env.MEMORYOS_INITIAL_OWNER_EMAIL,
        emailVerified: true,
        enabled: true
    }' |
        "$KCADM" update "users/$INITIAL_OWNER_UUID" \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM" \
            -f - >/dev/null
    echo "user=$MEMORYOS_INITIAL_OWNER_USERNAME subject=$INITIAL_OWNER_UUID action=$action profile=verified"
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
        current_mapper=$("$KCADM" get "clients/$CLIENT_UUID/protocol-mappers/models/$MAPPER_UUID" \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM")
        current_contract=$(printf '%s\n' "$current_mapper" |
            jq -cS '{name, protocol, protocolMapper, consentRequired, config}')
        desired_contract=$(jq -cS '{name, protocol, protocolMapper, consentRequired, config}' "$SCRIPT_DIR/$MAPPER_FILE")
        if [ "$current_contract" = "$desired_contract" ]; then
            echo "client=$CLIENT_ID mapper=$MAPPER_NAME action=unchanged"
        else
            "$KCADM" update "clients/$CLIENT_UUID/protocol-mappers/models/$MAPPER_UUID" \
                --config "$CONFIG_FILE" \
                -r "$TARGET_REALM" \
                -f "$SCRIPT_DIR/$MAPPER_FILE" >/dev/null
            echo "client=$CLIENT_ID mapper=$MAPPER_NAME action=updated"
        fi
    fi
}

jq --arg redirectUri "$MEMORYOS_BROWSER_REDIRECT_URI" \
    --arg publicUrl "$MEMORYOS_BROWSER_PUBLIC_URL" \
    '.rootUrl = $publicUrl
     | .baseUrl = "/"
     | .redirectUris = [$redirectUri]
     | .webOrigins = [$publicUrl]
     | .attributes["post.logout.redirect.uris"] = ($publicUrl + "/*")' \
    "$SCRIPT_DIR/memoryos-browser-client.json" >"$BROWSER_CLIENT_FILE"
jq --arg publicUrl "$MEMORYOS_MAILPIT_PUBLIC_URL" \
    '.rootUrl = $publicUrl
     | .redirectUris = [$publicUrl + "/oauth2/callback"]
     | .webOrigins = [$publicUrl]
     | .attributes["post.logout.redirect.uris"] = ($publicUrl + "/*")' \
    "$SCRIPT_DIR/memoryos-mailpit-client.json" >"$MAILPIT_CLIENT_FILE"


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

upsert_client memoryos-mailpit "$MAILPIT_CLIENT_FILE"
jq -cn '{secret: env.MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET}' |
    "$KCADM" update "clients/$CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f - >/dev/null
echo "client=memoryos-mailpit secret=updated"
