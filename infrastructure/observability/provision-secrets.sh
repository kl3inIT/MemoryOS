#!/usr/bin/env sh
set -eu
umask 077

# Run as the deployment administrator on the Linux staging host.
test "$(id -u)" = 0 || { echo "Run as root to set Grafana secret ownership" >&2; exit 1; }
secret_directory=${MEMORYOS_GRAFANA_SECRET_DIRECTORY:-/apps/memoryos/secrets/grafana}
test ! -L "$secret_directory" || { echo "Secret directory must not be a symlink" >&2; exit 1; }
mkdir -p "$secret_directory"
chmod 0700 "$secret_directory"
for name in admin-password.txt oidc-secret.txt; do
    secret_file=$secret_directory/$name
    test ! -L "$secret_file" || { echo "Secret file must not be a symlink" >&2; exit 1; }
    if [ ! -e "$secret_file" ]; then
        openssl rand -hex 32 >"$secret_file"
    fi
    test -s "$secret_file"
    # Compose file secrets are bind mounts: their host ownership is preserved.
    chown 472:472 "$secret_file"
    chmod 0400 "$secret_file"
done
echo "Grafana secret files provisioned; existing values preserved"
