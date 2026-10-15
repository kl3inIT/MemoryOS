#!/bin/sh
# OpenSandbox reads its API key from OPENSANDBOX_SERVER_API_KEY only; MemoryOS keeps every server secret in a file.
set -eu
key_file=${SANDBOX_API_KEY_FILE:?Set SANDBOX_API_KEY_FILE}
[ -s "$key_file" ] || { echo "start.sh: the API key file is missing or empty: $key_file" >&2; exit 1; }
OPENSANDBOX_SERVER_API_KEY=$(cat "$key_file")
export OPENSANDBOX_SERVER_API_KEY
exec opensandbox-server --config /etc/opensandbox/config.toml
