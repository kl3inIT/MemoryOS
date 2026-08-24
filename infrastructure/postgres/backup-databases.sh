#!/bin/sh
set -eu

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${BACKUP_DATABASES:?BACKUP_DATABASES is required}"
: "${BACKUP_DIRECTORY:=/backup}"

umask 077
mkdir -p "${BACKUP_DIRECTORY}"
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
manifest="${BACKUP_DIRECTORY}/${timestamp}.sha256"
: > "${manifest}"

for database_name in ${BACKUP_DATABASES}; do
    case "${database_name}" in
        '' | *[!a-zA-Z0-9_-]*)
            echo "invalid backup database name" >&2
            exit 1
            ;;
    esac

    archive="${BACKUP_DIRECTORY}/${database_name}-${timestamp}.dump"
    restore_list="${archive}.list"

    pg_dump \
        --format=custom \
        --file "${archive}" \
        --dbname "${database_name}"

    pg_restore --list "${archive}" > "${restore_list}"
    sha256sum "${archive}" "${restore_list}" >> "${manifest}"
    printf 'database=%s archive=%s restore_list=%s\n' \
        "${database_name}" \
        "$(basename "${archive}")" \
        "$(basename "${restore_list}")"
done

sha256sum --check "${manifest}"
printf 'manifest=%s\n' "$(basename "${manifest}")"
