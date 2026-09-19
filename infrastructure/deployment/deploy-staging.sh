#!/usr/bin/env bash
# CD deploys and finalizes healthy images. Operators explicitly select rollback.
set -Eeuo pipefail
umask 077
mode=${1:?deploy, rollback or finish}
release=${2:?verified SHA-workflowRun-workflowAttempt}
[[ "$release" =~ ^[0-9a-f]{40}-[1-9][0-9]*-[1-9][0-9]*$ ]]
[[ $EUID == 0 ]]
root=/apps/memoryos
state=$root/deployments
tx=$state/$release
mkdir -p "$state"
exec 9>"$state/lock"
flock --nonblock 9 || { echo 'Another staging operation owns the lock' >&2; exit 1; }

# Release images in images.env order. The interpreter starts executor containers from the last one
# on the host daemon, so it is pulled and verified here but is not a Compose service.
images=(api worker web interpreter interpreter-executor)

image_key() {
  local key=${1//-/_}
  printf 'MEMORYOS_%s_IMAGE' "${key^^}"
}

image_reference() {
  sed -n "s/^$(image_key "$1")=//p" "$2"
}

# A runtime accepted before MEM-110 has no interpreter.
has_interpreter() {
  grep -q "^$(image_key interpreter)=" "$1"
}

schema() {
  docker exec memoryos-postgres sh -c \
    'exec psql -U "$POSTGRES_USER" -d memoryos -At -c "$1"' sh \
    'SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank'
}

compose() {
  local file
  local args=(docker compose --project-name memoryos --env-file "$tx/$target.base.env" --env-file "$tx/$target.env")
  while IFS= read -r file; do args+=(-f "$file"); done < "$tx/$target.compose"
  "${args[@]}" "$@"
}

rollout() {
  compose up -d --no-deps --pull never --wait --wait-timeout 240 api
  compose up -d --no-deps --pull never --wait --wait-timeout 240 worker web
  if has_interpreter "$tx/$target.env"; then
    compose up -d --no-deps --pull never --wait --wait-timeout 240 interpreter
  fi
}

verify_runtime() {
  local component reference image sha
  local components=(api worker web)
  sha=$(sed -n 's/^MEMORYOS_RELEASE=//p' "$tx/$target.env")
  if has_interpreter "$tx/$target.env"; then
    components+=(interpreter)
    reference=$(image_reference interpreter-executor "$tx/$target.env")
    docker image inspect "$reference" | jq --exit-status --arg sha "$sha" '
      .[0].Config.Labels["org.opencontainers.image.revision"] == $sha
    ' > /dev/null
  fi
  for component in "${components[@]}"; do
    reference=$(image_reference "$component" "$tx/$target.env")
    image=$(docker image inspect --format '{{.Id}}' "$reference")
    docker inspect "memoryos-$component" | jq --exit-status --arg image "$image" --arg sha "$sha" '
      .[0] | .State.Running and (.State.Restarting | not) and .State.Health.Status == "healthy"
      and .Image == $image and .Config.Labels["org.opencontainers.image.revision"] == $sha
    ' > /dev/null
  done
}

if [[ "$mode" == rollback && ! -f "$state/pending" ]]; then
  echo 'No runtime mutation was reserved'; exit 2
fi
if [[ "$mode" == deploy ]]; then
  [[ ! -e "$state/pending" ]] || { echo 'Previous deployment requires recovery; see the CI/CD runbook' >&2; exit 1; }
  [[ ! -e "$tx" ]]
  [[ -f "$root/.env.staging" && ! -L "$root/.env.staging" ]]
  [[ "$(stat -c '%a' "$root/.env.staging")" == 600 ]]
  mkdir "$tx"
  cp "$root/.env.staging" "$tx/candidate.base.env"
  cp "$root/incoming/$release/"{manifest.json,configuration.tar,images.env,SHA256SUMS} "$tx/"
  (cd "$tx" && sha256sum --check --strict SHA256SUMS)
  jq --exit-status --arg sha "${release:0:40}" '
    .repository == "kl3inIT/MemoryOS" and .sha == $sha
  ' "$tx/manifest.json" > /dev/null
  [[ $(wc -l < "$tx/images.env") == 6 ]]
  for component in "${images[@]}"; do
    reference=$(image_reference "$component" "$tx/images.env")
    [[ "$reference" =~ ^ghcr.io/kl3init/memoryos-$component@sha256:[0-9a-f]{64}$ ]]
  done
  [[ "$(sed -n 's/^MEMORYOS_RELEASE=//p' "$tx/images.env")" == "${release:0:40}" ]]
  cp "$tx/images.env" "$tx/candidate.env"
  mkdir "$tx/source"
  tar --extract --file "$tx/configuration.tar" --directory "$tx/source" --no-same-owner --no-same-permissions
  for file in compose.base.yaml compose.staging.yaml compose.search.staging.yaml; do
    printf '%s\n' "$tx/source/infrastructure/deployment/$file" >> "$tx/candidate.compose"
  done

  # Capture actual image IDs and Compose files, including the previous SSH-built release.
  previous_components=(api worker web)
  if [[ -f "$state/current.env" ]] && has_interpreter "$state/current.env"; then
    previous_components+=(interpreter)
  fi
  previous=$(docker inspect "${previous_components[@]/#/memoryos-}" | jq --exit-status --argjson count "${#previous_components[@]}" '
    if length == $count and all(.[]; .State.Running and .State.Health.Status == "healthy")
      and ([.[].Config.Labels["org.opencontainers.image.revision"]] | unique | length) == 1
      and ([.[].Config.Labels["com.docker.compose.project.config_files"]] | unique | length) == 1
    then map({name: .Name, image: .Image, labels: .Config.Labels}) else error("Unhealthy or mixed runtime") end
  ')
  previous_sha=$(jq --raw-output '.[0].labels["org.opencontainers.image.revision"]' <<< "$previous")
  [[ "$previous_sha" =~ ^[0-9a-f]{40}$ ]]
  for component in "${previous_components[@]}"; do
    image=$(jq --raw-output --arg name "/memoryos-$component" '.[] | select(.name == $name) | .image' <<< "$previous")
    [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]]
    printf '%s=%s\n' "$(image_key "$component")" "$image" >> "$tx/previous.env"
  done
  if has_interpreter "$tx/previous.env"; then
    # Executors are not containers between runs; the accepted record holds their image.
    reference=$(image_reference interpreter-executor "$state/current.env")
    [[ "$reference" =~ ^ghcr.io/kl3init/memoryos-interpreter-executor@sha256:[0-9a-f]{64}$ ]]
    printf '%s=%s\n' "$(image_key interpreter-executor)" "$reference" >> "$tx/previous.env"
  fi
  printf 'MEMORYOS_RELEASE=%s\n' "$previous_sha" >> "$tx/previous.env"
  jq --raw-output '.[0].labels["com.docker.compose.project.config_files"] | split(",")[]' <<< "$previous" > "$tx/previous.compose"
  while IFS= read -r file; do
    [[ -f "$file" && "$(realpath "$file")" == "$root/"* ]]
  done < "$tx/previous.compose"
  if [[ -f "$state/current.env" ]]; then
    [[ "$(sed -n 's/^MEMORYOS_RELEASE=//p' "$state/current.env")" == "$previous_sha" ]]
    cmp --silent "$state/current.compose" "$tx/previous.compose"
    cp "$state/current.base.env" "$tx/previous.base.env"
  else
    # First promotion captures the existing operator-managed configuration.
    cp "$root/.env.staging" "$tx/previous.base.env"
  fi
  target=previous; compose config --quiet
  target=candidate; compose config --quiet
  # Compose config accepts a missing secret file, and rollout would then fail after the reservation.
  compose config --format json | jq --raw-output '.secrets // {} | .[].file // empty' | while IFS= read -r file; do
    [[ -f "$file" ]] || { echo "Missing Compose secret file: $file" >&2; exit 1; }
  done
  schema > "$tx/schema.before"
  while IFS='|' read -r version _checksum success; do
    [[ "$success" == t && "$version" =~ ^[0-9]+$ ]]
    compgen -G "$tx/source/core/src/main/resources/db/migration/V${version}__*.sql" > /dev/null || {
      echo 'Candidate predates an applied migration; operator compatibility review is required' >&2; exit 1;
    }
  done < "$tx/schema.before"
  # The job-scoped GHCR token arrives on stdin, never in arguments or the environment file.
  export DOCKER_CONFIG="$tx/registry"
  mkdir "$DOCKER_CONFIG"
  trap 'rm -f -- "$DOCKER_CONFIG/config.json"; rmdir -- "$DOCKER_CONFIG"' EXIT
  docker login ghcr.io --username "${3:?registry user}" --password-stdin
  compose pull api worker web interpreter
  docker pull --quiet "$(image_reference interpreter-executor "$tx/candidate.env")" > /dev/null
  for component in "${images[@]}"; do
    reference=$(image_reference "$component" "$tx/candidate.env")
    docker image inspect "$reference" | jq --exit-status --arg sha "${release:0:40}" '
      .[0].Config.Labels["org.opencontainers.image.revision"] == $sha
    ' > /dev/null
  done
  database_size=$(docker exec memoryos-postgres sh -c \
    'exec psql -U "$POSTGRES_USER" -d memoryos -At -c "SELECT pg_database_size(current_database())"')
  [[ "$database_size" =~ ^[0-9]+$ ]]
  available=$(df --output=avail --block-size=1 "$root" | tail -n 1)
  (( available > 2 * database_size + 2000000000 ))

  # Keep this reservation until health/revision verification and finalization.
  printf '%s\n' "$release" > "$state/pending"
  touch "$tx/writers-changing"
  target=previous; compose stop --timeout 45 worker api
  # The database user expands inside the existing PostgreSQL container.
  # shellcheck disable=SC2016
  timeout 300 docker exec memoryos-postgres sh -c \
    'exec pg_dump -U "$POSTGRES_USER" -d memoryos -Fc' > "$tx/database.dump"
  docker exec -i memoryos-postgres pg_restore --list < "$tx/database.dump" > "$tx/backup.catalogue"
  [[ -s "$tx/backup.catalogue" ]]
  sha256sum "$tx/database.dump" > "$tx/backup.sha256"
  target=candidate; rollout; verify_runtime
  echo 'Candidate healthy; finish records deployment, not business acceptance'
elif [[ "$mode" == rollback ]]; then
  if [[ ! -f "$state/pending" ]]; then echo 'No runtime mutation was reserved'; exit 2; fi
  [[ -f "$state/pending" && "$(cat "$state/pending")" == "$release" ]]
  if [[ ! -f "$tx/writers-changing" ]]; then
    rm -- "$state/pending"
    echo 'No writers changed; prior admission restored'; exit 2
  fi
  target=candidate; compose stop --timeout 45 worker api
  schema > "$tx/schema.after-failure"
  cmp --silent "$tx/schema.before" "$tx/schema.after-failure" || {
    echo 'Schema changed: writers stopped; operator recovery is required. No database restore was attempted.' >&2; exit 1;
  }
  if ! has_interpreter "$tx/previous.env"; then
    # The previous Compose files have no interpreter service to restore; leave the candidate one stopped.
    target=candidate; compose stop --timeout 65 interpreter
  fi
  target=previous; rollout; verify_runtime
  touch "$tx/rolled-back"
  echo 'Previous images restored and healthy; finish records recovery'
elif [[ "$mode" == finish ]]; then
  [[ -f "$state/pending" && "$(cat "$state/pending")" == "$release" ]]
  target=candidate
  if [[ -f "$tx/rolled-back" ]]; then target=previous; fi
  verify_runtime
  schema > "$tx/schema.accepted"
  cp "$tx/$target.env" "$state/current.env.new"
  mv "$state/current.env.new" "$state/current.env"
  cp "$tx/$target.compose" "$state/current.compose.new"
  mv "$state/current.compose.new" "$state/current.compose"
  cp "$tx/$target.base.env" "$state/current.base.env.new"
  mv "$state/current.base.env.new" "$state/current.base.env"
  printf '%s %s\n' "$release" "$target" > "$tx/result"
  rm -- "$state/pending"
  echo "Finalized $target runtime for workflow $release; business acceptance remains separate"
else
  echo 'Expected deploy, rollback or finish' >&2
  exit 1
fi
