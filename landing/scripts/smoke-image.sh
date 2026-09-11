#!/usr/bin/env bash
# Exercise a landing image as production runs it: read-only root, no capabilities, UID 101.
set -euo pipefail

image=${1:?Usage: smoke-image.sh <image>}
port=${LANDING_SMOKE_PORT:-18090}
origin="http://127.0.0.1:$port"
csp="default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'"

fail() {
  echo "landing smoke: $*" >&2
  exit 1
}

container=$(docker run --detach --read-only --tmpfs /tmp:size=16m \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --publish "127.0.0.1:$port:8080" "$image")

cleanup() {
  local status=$?
  if ((status != 0)); then
    docker logs "$container" >&2 || true
  fi
  docker rm --force "$container" >/dev/null
}
trap cleanup EXIT

for attempt in {1..20}; do
  if curl --silent --fail "$origin/healthz" >/dev/null; then
    break
  fi
  ((attempt == 20)) && fail "no answer on /healthz"
  sleep 0.5
done

status_of() {
  curl --silent --output /dev/null --write-out '%{http_code}' "$origin$1"
}

expect_header() {
  grep --quiet --ignore-case --fixed-strings "$2" <<<"$1" || fail "missing header: $2"
}

[[ $(docker inspect --format '{{.Config.User}}' "$container") == 101:101 ]] ||
  fail "image does not run as 101:101"

page_headers=$(curl --silent --show-error --fail --dump-header - --output /dev/null "$origin/")
expect_header "$page_headers" "Content-Security-Policy: $csp"
expect_header "$page_headers" "X-Content-Type-Options: nosniff"
expect_header "$page_headers" "X-Frame-Options: DENY"
expect_header "$page_headers" "Referrer-Policy: strict-origin-when-cross-origin"
expect_header "$page_headers" "Permissions-Policy: camera=()"
expect_header "$page_headers" "Cache-Control: no-cache"

page=$(curl --silent --show-error --fail "$origin/")
[[ $page =~ (/assets/[^\"]+\.js) ]] || fail "page references no hashed script"
asset_headers=$(curl --silent --show-error --fail --dump-header - --output /dev/null \
  "$origin${BASH_REMATCH[1]}")
expect_header "$asset_headers" "Cache-Control: public, max-age=31536000, immutable"

for path in /theme-init.js /robots.txt /sitemap.xml /favicon.svg /og-image.png; do
  [[ $(status_of "$path") == 200 ]] || fail "$path is not served"
done
[[ $(status_of /missing) == 404 ]] || fail "unknown paths must return 404"

echo "landing smoke: $image passed"
