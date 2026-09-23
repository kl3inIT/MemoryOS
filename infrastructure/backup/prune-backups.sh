#!/usr/bin/env bash
# Runs on a backup target. Keeps the newest 14 archives, and the oldest archive of each of the six
# most recent months; removes the rest. The host that writes backups cannot delete them — it
# reaches this directory through `rrsync -wo` — so retention has to happen here.
#
# Usage: prune-backups.sh <directory> [--dry-run]
set -Eeuo pipefail
directory=${1:?backup directory}
dry_run=false
[[ "${2:-}" == --dry-run ]] && dry_run=true
cd "$directory"

# Names carry a UTC timestamp, memoryos-<environment>-YYYYMMDDTHHMMSSZ, so sorting a name sorts
# its time.
mapfile -t newest_first < <(ls -1 memoryos-*-*.tar.zst.age 2>/dev/null | sort -r)
(( ${#newest_first[@]} > 0 )) || { echo 'No archives'; exit 0; }

month_of() { sed -E 's/^memoryos-[a-z]+-([0-9]{6}).*/\1/' <<< "$1"; }

declare -A keep=()
for archive in "${newest_first[@]:0:14}"; do keep[$archive]=1; done

declare -A first_of_month=()
for (( index = ${#newest_first[@]} - 1; index >= 0; index-- )); do
    archive=${newest_first[$index]}
    month=$(month_of "$archive")
    [[ -n "${first_of_month[$month]:-}" ]] || first_of_month[$month]=$archive
done
for month in $(printf '%s\n' "${!first_of_month[@]}" | sort -r | head -n 6); do
    keep[${first_of_month[$month]}]=1
done

removed=0
for archive in "${newest_first[@]}"; do
    [[ -n "${keep[$archive]:-}" ]] && continue
    if $dry_run; then echo "would remove $archive"; else rm -f -- "$archive" "$archive.sha256"; fi
    removed=$((removed + 1))
done
echo "kept ${#keep[@]} of ${#newest_first[@]} archives, removed $removed"
