#!/usr/bin/env bash
# Fails when a change adds a Flyway migration at or below the newest version its base already has, or edits a
# migration the base already has. Flyway refuses both only when an environment starts, and a release whose number
# another merge had already applied stopped staging's API (V176 behind V177, #701).
# Usage: check-migration-order.sh <base revision> <head revision>
set -euo pipefail

base=$1
head=$2
dir=memoryos-core/src/main/resources/db/migration

versions() {
  git ls-tree --name-only "$1" "$dir/" | sed -n 's|.*/V\([0-9][0-9]*\)__.*\.sql$|\1|p' | sort -n
}

failed=false
while IFS= read -r version; do
  printf '::error::Migration version V%s is used twice.\n' "$version"
  failed=true
done < <(versions "$head" | uniq -d)

newest=$(versions "$base" | tail -n 1)
newest=${newest:-0}
for version in $(comm -13 <(versions "$base" | sort) <(versions "$head" | sort) | sort -n); do
  if (( version <= newest )); then
    printf '::error::V%s is added below V%s, the newest migration on the base; renumber it above V%s.\n' \
      "$version" "$newest" "$newest"
    failed=true
  fi
done

while IFS= read -r path; do
  printf '::error file=%s::An applied migration is edited; add the next version instead.\n' "$path"
  failed=true
done < <(git diff --no-renames --diff-filter=M --name-only "$base" "$head" -- "$dir/")

if [[ "$failed" == true ]]; then exit 1; fi
printf 'Migrations after %s: V%s and above only.\n' "$(git rev-parse --short "$base")" "$((newest + 1))"
