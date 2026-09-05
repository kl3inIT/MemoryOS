#!/bin/sh
set -eu

MINIO_ROOT_PASSWORD=$(cat /run/secrets/minio_root_password)
if [ -z "$MINIO_ROOT_PASSWORD" ]; then
  echo "MinIO root password must be non-empty" >&2
  exit 1
fi
export MINIO_ROOT_PASSWORD

if [ -n "${MINIO_IDENTITY_OPENID_CONFIG_URL:-}" ]; then
  : "${MEMORYOS_MINIO_CONSOLE_OIDC_CLIENT_SECRET_FILE:?MEMORYOS_MINIO_CONSOLE_OIDC_CLIENT_SECRET_FILE is required when OpenID is enabled}"
  MINIO_IDENTITY_OPENID_CLIENT_SECRET=$(cat "$MEMORYOS_MINIO_CONSOLE_OIDC_CLIENT_SECRET_FILE")
  if [ -z "$MINIO_IDENTITY_OPENID_CLIENT_SECRET" ]; then
    echo "MinIO OpenID client secret must be non-empty" >&2
    exit 1
  fi
  export MINIO_IDENTITY_OPENID_CLIENT_SECRET
fi

exec minio server /data --address :9000 --console-address :9001
