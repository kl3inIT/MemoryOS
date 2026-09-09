#!/bin/sh
set -eu

umask 077

secret_file=/run/secrets/inference_api_key
auth_config=/tmp/inference-auth.conf

if [ ! -r "$secret_file" ]; then
  echo "Inference API key file is missing or unreadable." >&2
  exit 1
fi

api_key=$(tr -d '\r\n' <"$secret_file")
case "$api_key" in
  ''|*[!0-9a-f]*)
    echo "Inference API key must contain exactly 64 hexadecimal characters." >&2
    exit 1
    ;;
esac
if [ "${#api_key}" -ne 64 ]; then
  echo "Inference API key must contain exactly 64 hexadecimal characters." >&2
  exit 1
fi

{
  printf 'map $http_authorization $memoryos_inference_authorized {\n'
  printf '    default 0;\n'
  printf '    "Bearer %s" 1;\n' "$api_key"
  printf '}\n'
} >"$auth_config"
unset api_key

nginx -t -c /etc/memoryos/nginx.conf
exec nginx -c /etc/memoryos/nginx.conf -g 'daemon off;'
