#!/usr/bin/env bash
# Starts a Keycloak image against a throwaway PostgreSQL and proves the MemoryOS login theme renders.
#
# Keycloak does not fail when a realm names a theme it cannot find: it logs an error and draws the
# built-in page. So "the container is healthy" proves nothing about the theme; this asks for a login
# page that uses it and reads what comes back.
#
# Usage: smoke-test-image.sh <image>
set -Eeuo pipefail
image=${1:?Keycloak image to test}
run=memoryos-keycloak-smoke-$$
network=$run
password=smoke-test-only

cleanup() {
  docker rm --force "$run-keycloak" "$run-postgres" > /dev/null 2>&1 || true
  docker network rm "$network" > /dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$network" > /dev/null
docker run --detach --name "$run-postgres" --network "$network" \
  --env POSTGRES_USER=keycloak --env POSTGRES_PASSWORD="$password" --env POSTGRES_DB=keycloak \
  postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382 > /dev/null
for _ in $(seq 60); do
  docker exec "$run-postgres" pg_isready -U keycloak -d keycloak > /dev/null 2>&1 && break
  sleep 1
done

docker run --detach --name "$run-keycloak" --network "$network" --publish 127.0.0.1::8080 \
  --env KC_DB_URL="jdbc:postgresql://$run-postgres:5432/keycloak" \
  --env KC_DB_USERNAME=keycloak --env KC_DB_PASSWORD="$password" \
  --env KC_HOSTNAME=http://localhost:8080 --env KC_HTTP_ENABLED=true \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=admin --env KC_BOOTSTRAP_ADMIN_PASSWORD="$password" \
  "$image" > /dev/null

# Readiness answers 200 only when the whole server is up. The body lists each check with its own
# status, and those say UP while "Keycloak Initialized" is still DOWN, so the body is not asked.
ready=false
for _ in $(seq 120); do
  if docker exec "$run-keycloak" bash -c \
    '{ printf "GET /health/ready HTTP/1.0\r\n\r\n" >&0; head -n 1 | grep -q " 200 "; } 0<>/dev/tcp/127.0.0.1/9000' 2> /dev/null; then
    ready=true
    break
  fi
  sleep 2
done
if ! $ready; then
  docker logs "$run-keycloak" 2>&1 | tail -40 >&2
  echo 'Keycloak never reported ready' >&2
  exit 1
fi

kcadm() { docker exec "$run-keycloak" /opt/keycloak/bin/kcadm.sh "$@"; }
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password "$password" > /dev/null
kcadm update realms/master -s loginTheme=memoryos

port=$(docker port "$run-keycloak" 8080/tcp | head -n 1 | sed 's/.*://')
base="http://127.0.0.1:$port"
# The admin console client demands PKCE; any well-formed challenge renders the login page.
challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
page=$(curl --silent --fail --header 'Host: localhost:8080' \
  "$base/realms/master/protocol/openid-connect/auth?client_id=security-admin-console&response_type=code&scope=openid&code_challenge=$challenge&code_challenge_method=S256&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fadmin%2Fmaster%2Fconsole%2F")

stylesheet=$(grep -oE '/resources/[^"]+/login/memoryos/css/memoryos\.css' <<< "$page" | head -n 1 || true)
if [[ -z "$stylesheet" ]]; then
  docker logs "$run-keycloak" 2>&1 | grep -i theme >&2 || true
  echo 'The login page does not use the memoryos theme' >&2
  exit 1
fi
grep -q 'Continue to MemoryOS' <<< "$page" || { echo 'The login page lacks the MemoryOS messages' >&2; exit 1; }

# Every image the stylesheet names is served, so a renamed file cannot leave a blank header.
css=$(curl --silent --fail --header 'Host: localhost:8080' "$base$stylesheet")
while IFS= read -r reference; do
  if ! size=$(curl --silent --fail --header 'Host: localhost:8080' \
         "$base${stylesheet%css/memoryos.css}${reference#../}" | wc -c) || (( size == 0 )); then
    echo "The theme names $reference, which Keycloak does not serve" >&2
    exit 1
  fi
done < <(grep -oE 'url\("?\.\./img/[^")]+' <<< "$css" | sed -E 's/^url\("?//')

if docker logs "$run-keycloak" 2>&1 | grep -q 'Failed to find LOGIN theme'; then
  echo 'Keycloak could not find a login theme' >&2
  exit 1
fi
echo "Keycloak image serves the memoryos login theme: $stylesheet"
