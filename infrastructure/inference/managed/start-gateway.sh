#!/bin/sh
set -eu
umask 077

secret_file=/run/secrets/inference_api_key
auth_config=/tmp/inference-auth.conf

fail() {
  echo 'Inference gateway startup failed: KEY_OR_CONTROL_PERMISSIONS_INVALID' >&2
  exit 1
}

[ -f "$secret_file" ] && [ ! -L "$secret_file" ] && [ -r "$secret_file" ] || fail
[ "$(stat -c '%u:%g:%a' "$secret_file")" = '1654:1654:400' ] || fail
case "$(wc -c < "$secret_file" | tr -d ' ')" in
  64|65) ;;
  *) fail ;;
esac
api_key=$(cat "$secret_file")
case "$api_key" in
  ''|*[!0-9a-f]*) fail ;;
esac
[ "${#api_key}" -eq 64 ] || fail
[ -d /var/lib/memoryos-inference/control ] && [ -x /var/lib/memoryos-inference/control ] || fail

# Only a readonly host key and a private tmpfs configuration hold the credential.
# Never invoke nginx -T or shell tracing; both would expose the generated map.
{
  printf "map \$http_authorization \$memoryos_inference_authorized {\n"
  printf '    default 0;\n'
  printf '    "~^Bearer %s$" 1;\n' "$api_key"
  printf '}\n'
  printf "map \$host \$memoryos_inference_probe_authorization {\n"
  printf '    default "Bearer %s";\n' "$api_key"
  printf '}\n'
} > "$auth_config"
unset api_key

nginx -t -q -c /opt/memoryos-inference/nginx.conf
exec nginx -c /opt/memoryos-inference/nginx.conf -g 'daemon off;'
