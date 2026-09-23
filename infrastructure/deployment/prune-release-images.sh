#!/usr/bin/env bash
# Removes MemoryOS release images that no accepted release on this host still names.
#
# Keeps the images of the release that is running and of the one accepted before it, which is the
# release a rollback restores. "The two newest images" would be the wrong rule: the newest can be a
# candidate that failed, and then the running release and its predecessor are the ones deleted.
#
# The interpreter executor is kept by the same rule even though no container runs it between Python
# executions. That is exactly why `docker image prune -a` removed it on staging, the interpreter
# answered 503, and the next deployment refused to replace a runtime it could not call healthy.
#
# Only ghcr.io/kl3init/memoryos-* images are considered, which includes the Keycloak a release brings.
# PostgreSQL, an operator-run Keycloak such as staging's, MinIO, OpenSearch,
# Docling and the rest belong to the operator, not to a release, and are never touched.
#
# Usage: prune-release-images.sh [--dry-run]
set -Eeuo pipefail
umask 077

root=${MEMORYOS_ROOT:-/apps/memoryos}
state=$root/deployments
dry_run=false
[[ "${1:-}" == --dry-run ]] && dry_run=true

[[ -d "$state" ]] || { echo "No deployment state at $state; nothing to keep, so nothing pruned"; exit 0; }

# The deployment script holds this lock for the whole transaction. Waiting would only postpone a
# prune until after images it is about to need were pulled; skipping costs one day.
exec 9>"$state/lock"
flock --nonblock 9 || { echo 'A deployment owns the lock; nothing pruned'; exit 0; }
if [[ -e "$state/pending" ]]; then
  echo 'A deployment is reserved and unfinished; its candidate and previous images are both needed'
  exit 0
fi
[[ -f "$state/current.env" ]] || { echo 'No accepted release on this host; nothing pruned'; exit 0; }

# The environment files of the running release and of the accepted one before it. A finished
# transaction records "<release> <target>" in result, and its <target>.env names what ran.
keep_files=("$state/current.env")
while IFS= read -r result; do
  read -r _release target < "$result"
  [[ "$target" =~ ^(candidate|previous)$ ]] || continue
  env_file="$(dirname "$result")/$target.env"
  [[ -f "$env_file" ]] && keep_files+=("$env_file")
done < <(find "$state" -mindepth 2 -maxdepth 2 -name result -type f -printf '%T@ %p\n' \
           | sort -rn | head -n 2 | cut -d' ' -f2-)

# A value is either a registry reference (a candidate) or an image ID (a captured previous runtime).
declare -A keep=()
for file in "${keep_files[@]}"; do
  while IFS='=' read -r _key value; do
    [[ -n "$value" ]] || continue
    if [[ "$value" =~ ^sha256:[0-9a-f]{64}$ ]]; then
      keep[$value]=1
    elif id=$(docker image inspect --format '{{.Id}}' "$value" 2>/dev/null); then
      keep[$id]=1
    fi
  done < <(grep -E '^MEMORYOS_[A-Z_]+_IMAGE=' "$file")
done
if (( ${#keep[@]} == 0 )); then
  echo 'The accepted releases resolve to no local image; refusing to prune everything' >&2
  exit 1
fi

removed=0
kept=0
declare -A seen=()
while read -r id repository; do
  [[ "$repository" == ghcr.io/kl3init/memoryos-* ]] || continue
  [[ -n "${seen[$id]:-}" ]] && continue
  seen[$id]=1
  if [[ -n "${keep[$id]:-}" ]]; then
    kept=$((kept + 1))
    continue
  fi
  if $dry_run; then
    echo "would remove $repository $id"
  elif docker image rm "$id" > /dev/null 2>&1; then
    echo "removed $repository $id"
    removed=$((removed + 1))
  else
    # Still used by a container, or tagged elsewhere: leave it rather than force it.
    echo "left $repository $id (in use)"
  fi
done < <(docker images --no-trunc --format '{{.ID}} {{.Repository}}')

echo "kept $kept image(s) named by the running and previous releases; removed $removed"
