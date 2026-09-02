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
: "${MEMORYOS_KEYCLOAK_PROVISIONER_CLIENT_SECRET:?MEMORYOS_KEYCLOAK_PROVISIONER_CLIENT_SECRET is required}"
: "${MEMORYOS_PGWEB_PUBLIC_URL:?MEMORYOS_PGWEB_PUBLIC_URL is required}"
: "${MEMORYOS_PGWEB_OAUTH2_CLIENT_SECRET:?MEMORYOS_PGWEB_OAUTH2_CLIENT_SECRET is required}"
: "${MEMORYOS_REDISINSIGHT_PUBLIC_URL:?MEMORYOS_REDISINSIGHT_PUBLIC_URL is required}"
: "${MEMORYOS_REDISINSIGHT_OAUTH2_CLIENT_SECRET:?MEMORYOS_REDISINSIGHT_OAUTH2_CLIENT_SECRET is required}"
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
export MEMORYOS_KEYCLOAK_PROVISIONER_CLIENT_SECRET

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
for inspector_url in "$MEMORYOS_PGWEB_PUBLIC_URL" "$MEMORYOS_REDISINSIGHT_PUBLIC_URL"; do
    case "$inspector_url" in
        *'*'* | */oauth2/callback | */)
            echo "inspection public URLs must be exact HTTPS origins without wildcards, callbacks, or trailing slashes" >&2
            exit 1
            ;;
        https://*)
            ;;
        *)
            echo "inspection public URLs must use HTTPS" >&2
            exit 1
            ;;
    esac
done


command -v jq >/dev/null 2>&1 || {
    echo "jq is required" >&2
    exit 1
}
umask 077
export MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET
export MEMORYOS_PGWEB_OAUTH2_CLIENT_SECRET
export MEMORYOS_REDISINSIGHT_OAUTH2_CLIENT_SECRET

CONFIG_FILE=$(mktemp)
BROWSER_CLIENT_FILE=$(mktemp)
MAILPIT_CLIENT_FILE=$(mktemp)
PGWEB_CLIENT_FILE=$(mktemp)
REDISINSIGHT_CLIENT_FILE=$(mktemp)
PROVISIONER_CLIENT_FILE=$(mktemp)
cleanup() {
    rm -f \
        "$CONFIG_FILE" \
        "$BROWSER_CLIENT_FILE" \
        "$MAILPIT_CLIENT_FILE" \
        "$PGWEB_CLIENT_FILE" \
        "$REDISINSIGHT_CLIENT_FILE" \
        "$PROVISIONER_CLIENT_FILE"
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

configure_realm() {
    jq -cn '{
        displayName: "MemoryOS",
        displayNameHtml: "MemoryOS",
        registrationAllowed: false,
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
    echo "realm=$TARGET_REALM self-registration=disabled email-verification=required smtp=updated"
}

configure_realm

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
ensure_inspector_role_and_user() {
    if ! "$KCADM" get "roles/memoryos-inspector" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" >/dev/null 2>&1; then
        jq -cn '{
            name: "memoryos-inspector",
            description: "Read-only access to MemoryOS staging inspection tools"
        }' |
            "$KCADM" create roles \
                --config "$CONFIG_FILE" \
                -r "$TARGET_REALM" \
                -f - >/dev/null
    fi

    rows=$("$KCADM" get users \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -q exact=true \
        -q username=admin \
        --fields id,username)
    matches=$(printf '%s\n' "$rows" | jq -c '[.[] | select(.username == "admin")]')
    count=$(printf '%s\n' "$matches" | jq -r 'length')
    if [ "$count" -gt 1 ]; then
        echo "duplicate realm-local admin username" >&2
        exit 1
    fi
    INSPECTOR_USER_UUID=$(printf '%s\n' "$matches" | jq -r '.[0].id // empty')
    action=existing
    if [ -z "$INSPECTOR_USER_UUID" ]; then
        : "${MEMORYOS_INSPECTOR_ADMIN_EMAIL:?MEMORYOS_INSPECTOR_ADMIN_EMAIL is required when creating the realm-local admin}"
        : "${MEMORYOS_INSPECTOR_ADMIN_PASSWORD:?MEMORYOS_INSPECTOR_ADMIN_PASSWORD is required when creating the realm-local admin}"
        jq -cn '{
            username: "admin",
            email: env.MEMORYOS_INSPECTOR_ADMIN_EMAIL,
            emailVerified: true,
            enabled: true,
            credentials: [{
                type: "password",
                value: env.MEMORYOS_INSPECTOR_ADMIN_PASSWORD,
                temporary: false
            }]
        }' |
            "$KCADM" create users \
                --config "$CONFIG_FILE" \
                -r "$TARGET_REALM" \
                -f - >/dev/null
        rows=$("$KCADM" get users \
            --config "$CONFIG_FILE" \
            -r "$TARGET_REALM" \
            -q exact=true \
            -q username=admin \
            --fields id,username)
        INSPECTOR_USER_UUID=$(printf '%s\n' "$rows" | jq -r '[.[] | select(.username == "admin")][0].id // empty')
        if [ -z "$INSPECTOR_USER_UUID" ]; then
            echo "realm-local admin creation did not converge" >&2
            exit 1
        fi
        action=created
    fi

    "$KCADM" add-roles \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        --uid "$INSPECTOR_USER_UUID" \
        --rolename memoryos-inspector >/dev/null

    assigned_users=$("$KCADM" get "roles/memoryos-inspector/users" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        --fields id,username)
    unexpected_count=$(printf '%s\n' "$assigned_users" |
        jq -r --arg expected "$INSPECTOR_USER_UUID" '[.[] | select(.id != $expected)] | length')
    if [ "$unexpected_count" -ne 0 ]; then
        echo "memoryos-inspector must be assigned only to the realm-local admin user" >&2
        exit 1
    fi
    echo "role=memoryos-inspector user=admin action=$action"
}

grant_inspector_role_to_client() {
    "$KCADM" add-roles \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        --cid "$CLIENT_UUID" \
        --rolename memoryos-inspector >/dev/null
}


jq --arg redirectUri "$MEMORYOS_BROWSER_REDIRECT_URI" \
    --arg activationUri "$MEMORYOS_BROWSER_PUBLIC_URL/invite/activate" \
    --arg publicUrl "$MEMORYOS_BROWSER_PUBLIC_URL" \
    '.rootUrl = $publicUrl
     | .baseUrl = "/"
     | .redirectUris = [$redirectUri, $activationUri]
     | .webOrigins = [$publicUrl]
     | .attributes["post.logout.redirect.uris"] = ($publicUrl + "/*")' \
    "$SCRIPT_DIR/memoryos-browser-client.json" >"$BROWSER_CLIENT_FILE"
jq --arg publicUrl "$MEMORYOS_MAILPIT_PUBLIC_URL" \
    '.rootUrl = $publicUrl
     | .redirectUris = [$publicUrl + "/oauth2/callback"]
     | .webOrigins = [$publicUrl]
     | .attributes["post.logout.redirect.uris"] = ($publicUrl + "/*")' \
    "$SCRIPT_DIR/memoryos-mailpit-client.json" >"$MAILPIT_CLIENT_FILE"
jq --arg publicUrl "$MEMORYOS_PGWEB_PUBLIC_URL" \
    '.rootUrl = $publicUrl
     | .redirectUris = [$publicUrl + "/oauth2/callback"]
     | .webOrigins = [$publicUrl]
     | .attributes["post.logout.redirect.uris"] = ($publicUrl + "/*")' \
    "$SCRIPT_DIR/memoryos-pgweb-client.json" >"$PGWEB_CLIENT_FILE"
jq --arg publicUrl "$MEMORYOS_REDISINSIGHT_PUBLIC_URL" \
    '.rootUrl = $publicUrl
     | .redirectUris = [$publicUrl + "/oauth2/callback"]
     | .webOrigins = [$publicUrl]
     | .attributes["post.logout.redirect.uris"] = ($publicUrl + "/*")' \
    "$SCRIPT_DIR/memoryos-redisinsight-client.json" >"$REDISINSIGHT_CLIENT_FILE"
cp "$SCRIPT_DIR/memoryos-user-provisioner-client.json" "$PROVISIONER_CLIENT_FILE"


provision_initial_owner
ensure_inspector_role_and_user
upsert_client memoryos-integration "$SCRIPT_DIR/memoryos-client.json"

upsert_mapper memoryos-api-audience memoryos-audience-mapper.json

upsert_client memoryos-web "$BROWSER_CLIENT_FILE"
jq -cn '{secret: env.MEMORYOS_BROWSER_CLIENT_SECRET}' |
    "$KCADM" update "clients/$CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f - >/dev/null
echo "client=memoryos-web secret=updated"

upsert_client memoryos-user-provisioner "$PROVISIONER_CLIENT_FILE"
PROVISIONER_CLIENT_UUID=$CLIENT_UUID
jq -cn '{secret: env.MEMORYOS_KEYCLOAK_PROVISIONER_CLIENT_SECRET}' |
    "$KCADM" update "clients/$PROVISIONER_CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f - >/dev/null
SERVICE_ACCOUNT_ID=$("$KCADM" get "clients/$PROVISIONER_CLIENT_UUID/service-account-user" \
    --config "$CONFIG_FILE" \
    -r "$TARGET_REALM" \
    --fields id |
    jq -r '.id')
if [ -z "$SERVICE_ACCOUNT_ID" ] || [ "$SERVICE_ACCOUNT_ID" = "null" ]; then
    echo "memoryos-user-provisioner service account did not converge" >&2
    exit 1
fi
CLIENT_ID=realm-management
REALM_MANAGEMENT_UUID=$(find_client_uuid)
if [ -z "$REALM_MANAGEMENT_UUID" ]; then
    echo "realm-management client does not exist" >&2
    exit 1
fi
"$KCADM" add-roles \
    --config "$CONFIG_FILE" \
    -r "$TARGET_REALM" \
    --uid "$SERVICE_ACCOUNT_ID" \
    --cclientid realm-management \
    --rolename manage-users >/dev/null
PROVISIONER_ROLES=$("$KCADM" get \
    "users/$SERVICE_ACCOUNT_ID/role-mappings/clients/$REALM_MANAGEMENT_UUID" \
    --config "$CONFIG_FILE" \
    -r "$TARGET_REALM" \
    --fields name |
    jq -cS '[.[].name] | sort')
if [ "$PROVISIONER_ROLES" != '["manage-users"]' ]; then
    echo "memoryos-user-provisioner must have only realm-management manage-users" >&2
    exit 1
fi
echo "client=memoryos-user-provisioner secret=updated roles=manage-users"

upsert_client memoryos-mailpit "$MAILPIT_CLIENT_FILE"
jq -cn '{secret: env.MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET}' |
    "$KCADM" update "clients/$CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f - >/dev/null
echo "client=memoryos-mailpit secret=updated"

upsert_client memoryos-pgweb "$PGWEB_CLIENT_FILE"
jq -cn '{secret: env.MEMORYOS_PGWEB_OAUTH2_CLIENT_SECRET}' |
    "$KCADM" update "clients/$CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f - >/dev/null
grant_inspector_role_to_client
echo "client=memoryos-pgweb secret=updated role=memoryos-inspector"

upsert_client memoryos-redisinsight "$REDISINSIGHT_CLIENT_FILE"
jq -cn '{secret: env.MEMORYOS_REDISINSIGHT_OAUTH2_CLIENT_SECRET}' |
    "$KCADM" update "clients/$CLIENT_UUID" \
        --config "$CONFIG_FILE" \
        -r "$TARGET_REALM" \
        -f - >/dev/null
grant_inspector_role_to_client
echo "client=memoryos-redisinsight secret=updated role=memoryos-inspector"
