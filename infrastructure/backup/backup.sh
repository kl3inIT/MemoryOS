#!/usr/bin/env bash
# Nightly off-host backup of a MemoryOS deployment.
#
# Usage: backup.sh <environment>
#
# One archive holds everything this host could not rebuild on its own:
#   - PostgreSQL: the application and the identity realm, dumped by
#     infrastructure/postgres/backup-databases.sh, which also proves each dump restorable;
#   - object storage: the original files people uploaded;
#   - the secrets directory and the environment files, because a database backup without the
#     credential encryption keys restores rows nobody can decrypt;
#   - the reverse proxy's state and certificates.
# OpenSearch and Redis are left out: the index is rebuilt from PostgreSQL, and Redis holds only
# queues and replay buffers.
#
# The archive is encrypted to a public key before it leaves the host. The matching private key is
# kept off this host on purpose: if it lived here, losing the host would lose the only way to read
# its backups.
#
# Configuration: /etc/memoryos-backup.conf, see backup.conf.example.
set -Eeuo pipefail
umask 077

environment=${1:?staging or production}
[[ "$environment" =~ ^(staging|production)$ ]]
[[ $EUID == 0 ]]

root=${MEMORYOS_ROOT:-/apps/memoryos}
config=${MEMORYOS_BACKUP_CONFIG:-/etc/memoryos-backup.conf}
tools=${MEMORYOS_BACKUP_TOOLS:-/usr/local/lib/memoryos-backup}
# shellcheck source=/dev/null
. "$config"
: "${BACKUP_RECIPIENT:?BACKUP_RECIPIENT, the age public key, is required}"
: "${BACKUP_REMOTES:?BACKUP_REMOTES, one or more user@host targets, is required}"
: "${BACKUP_SSH_KEY:=/root/.ssh/memoryos-backup}"
: "${BACKUP_LOCAL_KEEP:=3}"
: "${PROXY_ROOT:=/apps/proxy}"

environment_file=$root/.env.$environment
local_directory=$root/backups
mkdir -p "$local_directory"

# One backup at a time. A deployment may run alongside: pg_dump reads one consistent snapshot.
exec 8>"$local_directory/.lock"
flock --nonblock 8 || { echo 'Another backup is running'; exit 0; }

stamp=$(date -u +%Y%m%dT%H%M%SZ)
name=memoryos-$environment-$stamp
work=$(mktemp -d "$local_directory/.work-XXXXXX")
trap 'rm -rf -- "$work"' EXIT

read_env() { sed -n "s/^$1=//p" "$environment_file" | tail -n 1; }

echo "=== PostgreSQL ==="
mkdir "$work/postgres"
# The dump runs from the same image as the server, so pg_dump never meets a newer server.
docker run --rm --network "${MEMORYOS_INTERNAL_NETWORK:-memoryos-internal}" \
    --entrypoint /bin/sh \
    -v "$tools/backup-databases.sh:/backup-tools/backup-databases.sh:ro" \
    -v "$work/postgres:/backup" \
    -e PGHOST=memoryos-postgres \
    -e PGUSER="$(read_env MEMORYOS_POSTGRES_ADMIN_USER || true)" \
    -e PGPASSWORD="$(read_env MEMORYOS_POSTGRES_ADMIN_PASSWORD)" \
    -e BACKUP_DATABASES="${BACKUP_DATABASES:-memoryos keycloak}" \
    "$(docker inspect --format '{{.Config.Image}}' memoryos-postgres)" \
    -c 'export PGUSER="${PGUSER:-memoryos_platform}"; exec /bin/sh /backup-tools/backup-databases.sh'

echo "=== Object storage ==="
minio=$(docker volume inspect --format '{{.Mountpoint}}' "${MEMORYOS_MINIO_VOLUME:-memoryos-minio-data}")
# Objects are written to a temporary name and renamed into place, so a file copy never sees half an
# object. Uploads still in progress live under .minio.sys/tmp and .minio.sys/multipart; a restore
# would discard them anyway.
tar --create --file "$work/objects.tar" --directory "$minio" \
    --exclude=./.minio.sys/tmp --exclude=./.minio.sys/multipart .

echo "=== Configuration and secrets ==="
configuration=("${root#/}/secrets" "${environment_file#/}")
[[ -f "$root/observability.env" ]] && configuration+=("${root#/}/observability.env")
[[ -d "$PROXY_ROOT" ]] && configuration+=("${PROXY_ROOT#/}")
tar --create --file "$work/configuration.tar" --directory / "${configuration[@]}"

echo "=== Seal ==="
cat > "$work/manifest" <<MANIFEST
environment=$environment
created=$stamp
host=$(hostname)
release=$(sed -n 's/^MEMORYOS_RELEASE=//p' "$root/deployments/current.env" 2>/dev/null || echo unknown)
MANIFEST
archive=$local_directory/$name.tar.zst.age
tar --create --directory "$work" manifest postgres objects.tar configuration.tar \
    | zstd --quiet -T0 -10 \
    | age --encrypt --recipient "$BACKUP_RECIPIENT" --output "$archive.partial"
mv "$archive.partial" "$archive"
(cd "$local_directory" && sha256sum "$(basename "$archive")" > "$(basename "$archive").sha256")
echo "  $(basename "$archive") $(du -h "$archive" | cut -f1)"

echo "=== Send ==="
# The target accepts writes only (rrsync -wo): this host can add a backup but cannot read or
# delete one, so whoever takes this host cannot take its history with it.
for remote in $BACKUP_REMOTES; do
    rsync --archive --partial --timeout=300 \
        -e "ssh -i $BACKUP_SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=yes" \
        "$archive" "$archive.sha256" "$remote:"
    echo "  sent to $remote"
done

# Keep the newest few here for a fast restore; the history lives on the targets.
ls -1t "$local_directory"/memoryos-"$environment"-*.tar.zst.age 2>/dev/null \
    | tail -n +"$((BACKUP_LOCAL_KEEP + 1))" \
    | while IFS= read -r old; do rm -f -- "$old" "$old.sha256"; done

date -u +%Y-%m-%dT%H:%M:%SZ > "$local_directory/last-success"
echo "Backup $name complete"
