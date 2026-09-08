#!/bin/sh
set -eu

MEMORYOS_DASHBOARDS_PASSWORD=$(cat /run/secrets/dashboards-password)
MEMORYOS_DASHBOARDS_OIDC_SECRET=$(cat /run/secrets/oidc-client-secret)
MEMORYOS_DASHBOARDS_COOKIE_PASSWORD=$(cat /run/secrets/cookie-password)
test -n "$MEMORYOS_DASHBOARDS_PASSWORD"
test -n "$MEMORYOS_DASHBOARDS_OIDC_SECRET"
test -n "$MEMORYOS_DASHBOARDS_COOKIE_PASSWORD"
export MEMORYOS_DASHBOARDS_PASSWORD MEMORYOS_DASHBOARDS_OIDC_SECRET MEMORYOS_DASHBOARDS_COOKIE_PASSWORD

exec /usr/share/opensearch-dashboards/bin/opensearch-dashboards
