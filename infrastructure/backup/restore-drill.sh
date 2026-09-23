#!/usr/bin/env bash
# Proves an archive restores, without touching the running deployment.
#
# Usage: restore-drill.sh <archive.tar.zst.age> <age-identity-file>
#
# Decrypts the archive, restores both databases into a throwaway PostgreSQL container on an
# isolated network, and counts what came back. It also opens the object and configuration tars,
# and checks that the credential encryption keys are present, because a database restored without
# them holds credentials nobody can decrypt. Nothing outside a temporary directory and one
# temporary container is created, and both are removed on exit.
set -Eeuo pipefail
umask 077

archive=${1:?archive}
identity=${2:?age identity file}
image=${MEMORYOS_POSTGRES_IMAGE:-postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382}

work=$(mktemp -d)
container=memoryos-restore-drill-$$
cleanup() { docker rm -f "$container" > /dev/null 2>&1 || true; rm -rf -- "$work"; }
trap cleanup EXIT

if [[ -f "$archive.sha256" ]]; then
    (cd "$(dirname "$archive")" && sha256sum --check --quiet "$(basename "$archive").sha256")
    echo "checksum: ok"
fi

age --decrypt --identity "$identity" "$archive" | zstd --decompress --quiet | tar --extract --directory "$work"
echo "decrypted: $(sed -n 's/^created=//p' "$work/manifest") from $(sed -n 's/^host=//p' "$work/manifest"), release $(sed -n 's/^release=//p' "$work/manifest")"

tar --list --file "$work/objects.tar" > /dev/null
echo "objects: $(tar --list --file "$work/objects.tar" | grep -c 'xl.meta$' || true) objects readable"

keys=$(tar --list --file "$work/configuration.tar" | grep -c 'secrets/encryption/.*\.txt$' || true)
(( keys > 0 )) || { echo 'configuration: the credential encryption keys are missing' >&2; exit 1; }
echo "configuration: $keys credential encryption keys present"

docker run --detach --name "$container" --network none \
    -e POSTGRES_PASSWORD=drill -e POSTGRES_USER=drill "$image" > /dev/null
for _ in $(seq 1 30); do
    docker exec "$container" pg_isready -U drill > /dev/null 2>&1 && break
    sleep 2
done

for dump in "$work"/postgres/*.dump; do
    database=$(basename "$dump" | sed -E 's/-[0-9]{8}T[0-9]{6}Z\.dump$//')
    docker exec "$container" createdb -U drill "$database"
    # Owners and grants name roles this throwaway server does not have; the data is what is proved.
    docker exec -i "$container" pg_restore -U drill --dbname "$database" --no-owner --no-privileges \
        --exit-on-error < "$dump"
    tables=$(docker exec "$container" psql -U drill -d "$database" -At -c \
        "select count(*) from information_schema.tables where table_schema = 'public'")
    echo "restored $database: $tables tables"
done

if docker exec "$container" psql -U drill -d memoryos -At -c "select 1 from tenants limit 1" > /dev/null 2>&1; then
    echo "memoryos: $(docker exec "$container" psql -U drill -d memoryos -At -c 'select count(*) from tenants') tenant(s), $(docker exec "$container" psql -U drill -d memoryos -At -c 'select max(version::int) from flyway_schema_history') migrations"
fi
echo "Restore drill passed"
