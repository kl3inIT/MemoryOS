#!/usr/bin/env bash
# CD deploys and finalizes healthy images. Operators explicitly select rollback.
# Usage: deploy.sh <deploy|rollback|finish> <release> <environment> [registry-user]
# The environment names the configuration this host runs: it selects the environment file and the
# Compose overlays. One host runs one environment; the deployment state directory is shared.
set -Eeuo pipefail
umask 077
mode=${1:?deploy, rollback or finish}
release=${2:?verified SHA-workflowRun-workflowAttempt}
environment=${3:?staging or production}
[[ "$release" =~ ^[0-9a-f]{40}-[1-9][0-9]*-[1-9][0-9]*$ ]]
[[ "$environment" =~ ^(staging|production)$ ]]
[[ $EUID == 0 ]]
root=/apps/memoryos
environment_file=$root/.env.$environment
state=$root/deployments
tx=$state/$release
mkdir -p "$state"
exec 9>"$state/lock"
flock --nonblock 9 || { echo "Another $environment operation owns the lock" >&2; exit 1; }

# Release images in images.env order. The interpreter starts executor containers from
# interpreter-executor on the host daemon, so it is pulled and verified here but is not a Compose
# service.
images=(api worker web interpreter interpreter-executor keycloak)

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

# Keycloak belongs to the release only where the environment file leaves it to the release. A
# runtime accepted before it joined, and a host that names its own Keycloak image, have none.
has_keycloak() {
  grep -q "^$(image_key keycloak)=" "$1"
}

# Empty before the first deployment: Flyway creates its history table when it first runs, and
# selecting from a table that does not exist is an error rather than an empty result.
schema() {
  docker exec memoryos-postgres sh -c \
    'exec psql -U "$POSTGRES_USER" -d memoryos -At -c "$1"' sh \
    "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"
}

# True once Flyway has run here. Asked separately because the query above cannot name a table that
# does not exist, and a first deployment has no history to compare against.
has_schema_history() {
  [[ "$(docker exec memoryos-postgres sh -c \
    'exec psql -U "$POSTGRES_USER" -d memoryos -At -c "$1"' sh \
    "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")" == t ]]
}

compose() {
  local file
  local args=(docker compose --project-name memoryos --env-file "$tx/$target.base.env" --env-file "$tx/$target.env")
  while IFS= read -r file; do args+=(-f "$file"); done < "$tx/$target.compose"
  "${args[@]}" "$@"
}

rollout() {
  # Every service is recreated from this transaction's files, even when Compose sees no change in
  # it, as when the running release is deployed again: the containers then all name one transaction.
  # Before the API, which signs people in through it.
  if has_keycloak "$tx/$target.env"; then
    compose up -d --no-deps --pull never --force-recreate --wait --wait-timeout 240 keycloak
  fi
  compose up -d --no-deps --pull never --force-recreate --wait --wait-timeout 240 api
  compose up -d --no-deps --pull never --force-recreate --wait --wait-timeout 240 worker web
  if has_interpreter "$tx/$target.env"; then
    compose up -d --no-deps --pull never --force-recreate --wait --wait-timeout 240 interpreter
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
  if has_keycloak "$tx/$target.env"; then
    components+=(keycloak)
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
  [[ -f "$environment_file" && ! -L "$environment_file" ]]
  [[ "$(stat -c '%a' "$environment_file")" == 600 ]]
  mkdir "$tx"
  cp "$environment_file" "$tx/candidate.base.env"
  cp "$root/incoming/$release/"{manifest.json,configuration.tar,images.env,SHA256SUMS} "$tx/"
  (cd "$tx" && sha256sum --check --strict SHA256SUMS)
  jq --exit-status --arg sha "${release:0:40}" '
    .repository == "kl3inIT/MemoryOS" and .sha == $sha
  ' "$tx/manifest.json" > /dev/null
  [[ $(wc -l < "$tx/images.env") == 7 ]]
  for component in "${images[@]}"; do
    reference=$(image_reference "$component" "$tx/images.env")
    [[ "$reference" =~ ^ghcr.io/kl3init/memoryos-$component@sha256:[0-9a-f]{64}$ ]]
  done
  [[ "$(sed -n 's/^MEMORYOS_RELEASE=//p' "$tx/images.env")" == "${release:0:40}" ]]
  cp "$tx/images.env" "$tx/candidate.env"
  # The environment file names a Keycloak image where an operator runs Keycloak: staging shares one
  # with OrgMemory, whose realm needs a theme only the OrgMemory image carries. The release then
  # leaves Keycloak alone, and the environment file's image, not the release's, is the one Compose
  # sees, because the release's value would otherwise override it.
  if grep -q "^$(image_key keycloak)=" "$environment_file"; then
    sed -i "/^$(image_key keycloak)=/d" "$tx/candidate.env"
    echo 'Keycloak is managed on this host, not by the release'
  fi
  mkdir "$tx/source"
  tar --extract --file "$tx/configuration.tar" --directory "$tx/source" --no-same-owner --no-same-permissions
  # The source is what git holds, and the services started from these Compose files read their
  # scripts and configuration from it as their own users: Grafana, Prometheus and the collector
  # are not root. Everything else in the transaction, and in the state directory, stays readable
  # by root alone; traversing a directory reveals no file whose own mode forbids reading it.
  chmod -R u=rwX,go=rX "$tx/source"
  chmod o+x "$state" "$tx"
  for file in compose.base.yaml "compose.$environment.yaml" "compose.search.$environment.yaml"; do
    printf '%s\n' "$tx/source/infrastructure/deployment/$file" >> "$tx/candidate.compose"
  done

  # Nothing has ever run here when the api container is absent. Asked of the runtime rather than
  # of current.env, because that file is also absent on a host whose runtime was built over SSH
  # before this script existed, and that host does have something to roll back to.
  #
  # Recorded as a file: rollback and finish are separate invocations of this script.
  if ! docker inspect memoryos-api > /dev/null 2>&1; then
    touch "$tx/first-deployment"
    echo 'First deployment on this host: nothing to capture, and rollback will have no target'
  fi

  if [[ ! -f "$tx/first-deployment" ]]; then
    # Capture actual image IDs and Compose files, including the previous SSH-built release.
    previous_components=(api worker web)
    if [[ -f "$state/current.env" ]] && has_interpreter "$state/current.env"; then
      previous_components+=(interpreter)
    fi
    # Only once a release put it there: before that, Keycloak carries another image's revision
    # label and another Compose project's files, and would read as a mixed runtime.
    if [[ -f "$state/current.env" ]] && has_keycloak "$state/current.env"; then
      previous_components+=(keycloak)
    fi
    # One release's Compose files may carry two transactions' paths: a runtime last deployed before
    # rollouts forced recreation kept the containers Compose saw no change in, labelled with an
    # earlier transaction's copy of the same files. The transaction directory is left out of the
    # comparison; the api container, inspected first, names the files the record must match.
    previous=$(docker inspect "${previous_components[@]/#/memoryos-}" | jq --exit-status --argjson count "${#previous_components[@]}" '
      if length == $count and all(.[]; .State.Running and .State.Health.Status == "healthy")
        and ([.[].Config.Labels["org.opencontainers.image.revision"]] | unique | length) == 1
        and ([.[].Config.Labels["com.docker.compose.project.config_files"]
              | gsub("/deployments/[^/,]+/"; "/deployments/*/")] | unique | length) == 1
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
      cp "$environment_file" "$tx/previous.base.env"
    fi
    target=previous; compose config --quiet
  fi
  target=candidate; compose config --quiet
  # Compose config accepts a missing secret file, and rollout would then fail after the reservation.
  compose config --format json | jq --raw-output '.secrets // {} | .[].file // empty' | while IFS= read -r file; do
    [[ -f "$file" ]] || { echo "Missing Compose secret file: $file" >&2; exit 1; }
  done
  if has_schema_history; then schema > "$tx/schema.before"; else : > "$tx/schema.before"; fi
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
  docker login ghcr.io --username "${4:?registry user}" --password-stdin
  compose pull api worker web interpreter
  if has_keycloak "$tx/candidate.env"; then compose pull keycloak; fi
  docker pull --quiet "$(image_reference interpreter-executor "$tx/candidate.env")" > /dev/null
  for component in "${images[@]}"; do
    reference=$(image_reference "$component" "$tx/candidate.env")
    [[ -n "$reference" ]] || continue
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
  if [[ ! -f "$tx/first-deployment" ]]; then target=previous; compose stop --timeout 45 worker api; fi
  # The database user expands inside the existing PostgreSQL container.
  # shellcheck disable=SC2016
  timeout 300 docker exec memoryos-postgres sh -c \
    'exec pg_dump -U "$POSTGRES_USER" -d memoryos -Fc' > "$tx/database.dump"
  docker exec -i memoryos-postgres pg_restore --list < "$tx/database.dump" > "$tx/backup.catalogue"
  [[ -s "$tx/backup.catalogue" ]]
  sha256sum "$tx/database.dump" > "$tx/backup.sha256"
  if has_keycloak "$tx/candidate.env"; then
    # A newer Keycloak migrates its database as it starts, and no older image can read it after.
    # shellcheck disable=SC2016
    timeout 300 docker exec memoryos-postgres sh -c       'exec pg_dump -U "$POSTGRES_USER" -d keycloak -Fc' > "$tx/keycloak.dump"
    docker exec -i memoryos-postgres pg_restore --list < "$tx/keycloak.dump" > "$tx/keycloak.catalogue"
    [[ -s "$tx/keycloak.catalogue" ]]
    sha256sum "$tx/keycloak.dump" >> "$tx/backup.sha256"
  fi
  target=candidate; rollout; verify_runtime
  echo 'Candidate healthy; finish records deployment, not business acceptance'
elif [[ "$mode" == rollback ]]; then
  # Whether a reservation exists at all is answered before the modes divide; what reaches here has
  # one, and only has to be the one this invocation names.
  [[ "$(cat "$state/pending")" == "$release" ]]
  if [[ ! -f "$tx/writers-changing" ]]; then
    rm -- "$state/pending"
    echo 'No writers changed; prior admission restored'; exit 2
  fi
  if [[ -f "$tx/first-deployment" ]]; then
    # There is no earlier runtime to put back, and the reservation stays so the state on this host
    # keeps saying that somebody has to look. The database was empty when this began, so recovery
    # is to take the stack down, discard its volumes and deploy again, not to restore anything.
    echo 'First deployment on this host: no previous runtime exists to restore.' >&2
    echo 'Stop the candidate api, worker, web and interpreter, then remove the reservation.' >&2
    echo 'Keep the database: the same volume holds the identity realm, and migrations already applied' >&2
    echo 'are carried by the next release as well. See the CI/CD runbook.' >&2
    exit 1
  fi
  target=candidate; compose stop --timeout 45 worker api
  if has_schema_history; then schema > "$tx/schema.after-failure"; else : > "$tx/schema.after-failure"; fi
  cmp --silent "$tx/schema.before" "$tx/schema.after-failure" || {
    echo 'Schema changed: writers stopped; operator recovery is required. No database restore was attempted.' >&2; exit 1;
  }
  if ! has_interpreter "$tx/previous.env"; then
    # The previous Compose files have no interpreter service to restore; leave the candidate one stopped.
    target=candidate; compose stop --timeout 65 interpreter
  fi
  # A previous runtime without a release Keycloak leaves the candidate's running: stopping it would
  # sign nobody in, and restoring the operator's image is the operator's call.
  target=previous; rollout; verify_runtime
  touch "$tx/rolled-back"
  echo 'Previous images restored and healthy; finish records recovery'
elif [[ "$mode" == finish ]]; then
  [[ -f "$state/pending" && "$(cat "$state/pending")" == "$release" ]]
  target=candidate
  if [[ -f "$tx/rolled-back" ]]; then target=previous; fi
  verify_runtime
  if has_schema_history; then schema > "$tx/schema.accepted"; else : > "$tx/schema.accepted"; fi
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
