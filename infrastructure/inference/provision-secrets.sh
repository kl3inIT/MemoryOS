#!/bin/sh
set -eu

secret_directory=${1:?usage: provision-secrets.sh <secret-directory>}
secret_file="${secret_directory}/api-key.txt"

umask 077
mkdir -p "$secret_directory"
if [ ! -e "$secret_file" ]; then
  openssl rand -hex 32 | tr -d '\n' >"$secret_file"
fi

api_key=$(tr -d '\r\n' <"$secret_file")
case "$api_key" in
  ''|*[!0-9a-f]*)
    printf 'Invalid inference API key in %s\n' "$secret_file" >&2
    exit 65
    ;;
esac
if [ "${#api_key}" -ne 64 ]; then
  printf 'Invalid inference API key in %s\n' "$secret_file" >&2
  exit 65
fi
printf '%s' "$api_key" >"${secret_file}.tmp"
unset api_key
chmod 0600 "${secret_file}.tmp"
mv "${secret_file}.tmp" "$secret_file"

printf 'Inference secret is complete in %s\n' "$secret_directory"
