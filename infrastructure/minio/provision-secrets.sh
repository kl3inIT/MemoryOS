#!/bin/sh
set -eu

secret_directory=${1:?usage: provision-secrets.sh <secret-directory>}
root_secret="${secret_directory}/root-password.txt"
api_secret="${secret_directory}/api-secret-key.txt"
worker_secret="${secret_directory}/worker-secret-key.txt"

present=0
for secret in "$root_secret" "$api_secret" "$worker_secret"; do
  if [ -e "$secret" ]; then
    present=$((present + 1))
  fi
done

if [ "$present" -ne 0 ] && [ "$present" -ne 3 ]; then
  printf 'Refusing partial MinIO secret set in %s\n' "$secret_directory" >&2
  exit 65
fi

umask 077
mkdir -p "$secret_directory"
if [ "$present" -eq 0 ]; then
  openssl rand -hex 24 > "$root_secret"
  openssl rand -hex 24 > "$api_secret"
  openssl rand -hex 24 > "$worker_secret"
fi
chmod 0600 "$root_secret" "$api_secret" "$worker_secret"
printf 'MinIO secret set is complete in %s\n' "$secret_directory"
