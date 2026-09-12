#!/usr/bin/env bash
# CD deploys and finalizes healthy images. Operators explicitly select rollback.
set -Eeuo pipefail
umask 077
mode=${1:?deploy, rollback, finish, drain, resume, rotate-key, complete-rotation or serving-rollback}
release=${2:?verified SHA-workflowRun-workflowAttempt}
[[ "$release" =~ ^[0-9a-f]{40}-[1-9][0-9]*-[1-9][0-9]*$ ]]
[[ $EUID == 0 ]]
root=/apps/memoryos
state=$root/deployments
tx=$state/$release
mkdir -p "$state"
exec 9>"$state/lock"
flock --nonblock 9 || { echo 'Another staging operation owns the lock' >&2; exit 1; }

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
}

verify_runtime() {
  local component reference image sha
  sha=$(sed -n 's/^MEMORYOS_RELEASE=//p' "$tx/$target.env")
  for component in api worker web; do
    reference=$(sed -n "s/^MEMORYOS_${component^^}_IMAGE=//p" "$tx/$target.env")
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
if [[ "$mode" != deploy ]]; then
  # The selected release owns recovery code; never source a checkout or mutable research launcher.
  # shellcheck source=infrastructure/deployment/inference-operations.sh
  source "$tx/source/infrastructure/deployment/inference-operations.sh"
fi

if [[ "$mode" == deploy ]]; then
  [[ ! -e "$state/pending" ]] || { echo 'Previous deployment requires recovery; see the CI/CD runbook' >&2; exit 1; }
  [[ ! -e "$tx" ]]
  [[ -f "$root/.env.staging" && ! -L "$root/.env.staging" ]]
  [[ "$(stat -c '%a' "$root/.env.staging")" == 600 ]]
  mkdir "$tx"
  cp "$root/.env.staging" "$tx/candidate.base.env"
  cp "$root/incoming/$release/"{manifest.json,configuration.tar,images.env,serving.sha256,SHA256SUMS} "$tx/"
  (cd "$tx" && sha256sum --check --strict SHA256SUMS)
  jq --exit-status --arg sha "${release:0:40}" '
    .repository == "kl3inIT/MemoryOS" and .sha == $sha
  ' "$tx/manifest.json" > /dev/null
  [[ $(wc -l < "$tx/images.env") == 4 ]]
  for component in api worker web; do
    reference=$(sed -n "s/^MEMORYOS_${component^^}_IMAGE=//p" "$tx/images.env")
    [[ "$reference" =~ ^ghcr.io/kl3init/memoryos-$component@sha256:[0-9a-f]{64}$ ]]
  done
  [[ "$(sed -n 's/^MEMORYOS_RELEASE=//p' "$tx/images.env")" == "${release:0:40}" ]]
  cp "$tx/images.env" "$tx/candidate.env"
  mkdir "$tx/source"
  tar --extract --file "$tx/configuration.tar" --directory "$tx/source" --no-same-owner --no-same-permissions
  # These tracked mounts contain no secrets. Extraction follows umask077, but serving/monitoring run as non-root.
  chmod -R a+rX "$tx/source/infrastructure/inference/managed" "$tx/source/infrastructure/observability"
  # shellcheck source=infrastructure/deployment/inference-operations.sh
  source "$tx/source/infrastructure/deployment/inference-operations.sh"
  for file in compose.base.yaml compose.staging.yaml compose.search.staging.yaml compose.inference.application.yaml; do
    printf '%s\n' "$tx/source/infrastructure/deployment/$file" >> "$tx/candidate.compose"
  done

  # Capture actual image IDs and Compose files, including the previous SSH-built release.
  previous=$(docker inspect memoryos-api memoryos-worker memoryos-web | jq --exit-status '
    if length == 3 and all(.[]; .State.Running and .State.Health.Status == "healthy")
      and ([.[].Config.Labels["org.opencontainers.image.revision"]] | unique | length) == 1
      and ([.[].Config.Labels["com.docker.compose.project.config_files"]] | unique | length) == 1
    then map({name: .Name, image: .Image, labels: .Config.Labels}) else error("Unhealthy or mixed runtime") end
  ')
  previous_sha=$(jq --raw-output '.[0].labels["org.opencontainers.image.revision"]' <<< "$previous")
  [[ "$previous_sha" =~ ^[0-9a-f]{40}$ ]]
  for component in api worker web; do
    image=$(jq --raw-output --arg name "/memoryos-$component" '.[] | select(.name == $name) | .image' <<< "$previous")
    [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]]
    printf 'MEMORYOS_%s_IMAGE=%s\n' "${component^^}" "$image" >> "$tx/previous.env"
  done
  printf 'MEMORYOS_RELEASE=%s\n' "$previous_sha" >> "$tx/previous.env"
  jq --raw-output '.[0].labels["com.docker.compose.project.config_files"] | split(",")[]' <<< "$previous" > "$tx/previous.compose"
  while IFS= read -r file; do
    [[ -f "$file" && "$(realpath "$file")" == "$root/"* ]]
  done < "$tx/previous.compose"
  if [[ -f "$state/current.env" ]]; then
    [[ "$(sed -n 's/^MEMORYOS_RELEASE=//p' "$state/current.env")" == "$previous_sha" ]]
    cmp --silent "$state/current.compose" "$tx/previous.compose"
    cp "$state/current.base.env" "$tx/previous.base.env"
    # Image IDs come from the live runtime; private-network identity comes from its accepted configuration.
    sed -n '/^MEMORYOS_INFERENCE_CLIENT_NETWORK=/p' "$state/current.env" >> "$tx/previous.env"
  else
    # First promotion captures the existing operator-managed configuration.
    cp "$root/.env.staging" "$tx/previous.base.env"
  fi
  inference_prepare
  target=previous; compose config --quiet
  target=candidate; compose config --quiet
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
  compose pull api worker web
  for component in api worker web; do
    reference=$(sed -n "s/^MEMORYOS_${component^^}_IMAGE=//p" "$tx/candidate.env")
    docker image inspect "$reference" | jq --exit-status --arg sha "${release:0:40}" '
      .[0].Config.Labels["org.opencontainers.image.revision"] == $sha
    ' > /dev/null
  done
  profile=$(jq -er '.model.tokenizerProfile' "$tx/source/infrastructure/inference/managed/manifest.json")
  api_reference=$(sed -n 's/^MEMORYOS_API_IMAGE=//p' "$tx/candidate.env")
  supported=$(docker image inspect "$api_reference" | jq -er '.[0].Config.Labels["io.memoryos.chat.tokenizer-profiles"]')
  [[ ",$supported," == *",$profile,"* ]] || { echo 'Candidate API image lacks the managed tokenizer profile' >&2; exit 1; }
  database_size=$(docker exec memoryos-postgres sh -c \
    'exec psql -U "$POSTGRES_USER" -d memoryos -At -c "SELECT pg_database_size(current_database())"')
  [[ "$database_size" =~ ^[0-9]+$ ]]
  available=$(df --output=avail --block-size=1 "$root" | tail -n 1)
  (( available > 2 * database_size + 2000000000 ))

  # Keep this reservation until health/revision verification and finalization.
  printf '%s\n' "$release" > "$state/pending"
  if [[ -f "$tx/previous.inference.source" ]]; then
    serving_target=previous; inference_drain
  fi
  touch "$tx/writers-changing"
  target=previous; compose stop --timeout 45 worker api
  # The database user expands inside the existing PostgreSQL container.
  # shellcheck disable=SC2016
  timeout 300 docker exec memoryos-postgres sh -c \
    'exec pg_dump -U "$POSTGRES_USER" -d memoryos -Fc' > "$tx/database.dump"
  docker exec -i memoryos-postgres pg_restore --list < "$tx/database.dump" > "$tx/backup.catalogue"
  [[ -s "$tx/backup.catalogue" ]]
  sha256sum "$tx/database.dump" > "$tx/backup.sha256"
  serving_target=candidate; inference_start; inference_resume
  inference_monitoring_apply
  target=candidate; rollout; verify_runtime
  echo 'Candidate healthy; finish records deployment, not business acceptance'
elif [[ "$mode" == rollback ]]; then
  if [[ ! -f "$state/pending" ]]; then echo 'No runtime mutation was reserved'; exit 2; fi
  [[ -f "$state/pending" && "$(cat "$state/pending")" == "$release" ]]
  [[ ! -f "$tx/active-operation" ]] || { echo 'Serving-only operation requires explicit recovery; application rollback refused' >&2; exit 1; }
  if [[ ! -f "$tx/writers-changing" ]]; then
    if [[ -f "$tx/previous.inference.source" ]]; then
      serving_target=previous; inference_paths; inference_resume
    fi
    rm -- "$state/pending"
    echo 'No writers changed; prior admission restored'; exit 2
  fi
  serving_target=candidate; inference_paths
  touch "$serving_control/maintenance"; chmod 644 "$serving_control/maintenance"
  target=candidate; compose stop --timeout 45 worker api
  schema > "$tx/schema.after-failure"
  cmp --silent "$tx/schema.before" "$tx/schema.after-failure" || {
    echo 'Schema changed: writers stopped; operator recovery is required. No database restore was attempted.' >&2; exit 1;
  }
  if [[ -f "$tx/previous.inference.source" ]]; then
    serving_target=candidate; inference_drain
    serving_target=previous; inference_compatible_restore
    inference_start; inference_resume; inference_monitoring_apply
  else
    serving_target=candidate; inference_paths
    touch "$serving_control/maintenance"; chmod 644 "$serving_control/maintenance"
    inference_compose stop --timeout 150 inference-gateway vllm
    inference_compose rm --force vllm inference-gateway
  fi
  target=previous; rollout; verify_runtime
  touch "$tx/rolled-back"
  echo 'Previous images restored and healthy; finish records recovery'
elif [[ "$mode" == finish ]]; then
  [[ -f "$state/pending" && "$(cat "$state/pending")" == "$release" ]]
  operation_parent=$tx
  if [[ -f "$tx/active-operation" ]]; then
    inference_operation_open
    [[ ! -f "$tx/rotation.started" || -f "$tx/rotation.completed" ]] || {
      echo 'Credential rotation is incomplete; admission/reservation retained' >&2; exit 1;
    }
    schema > "$tx/schema.finish"
    cmp --silent "$tx/schema.before" "$tx/schema.finish" || {
      echo 'Schema changed during serving-only operation; operator recovery required' >&2; exit 1;
    }
  fi
  target=candidate
  if [[ -f "$tx/rolled-back" ]]; then target=previous; fi
  verify_runtime
  if [[ -f "$tx/serving-only" ]]; then
    inference_monitoring_apply
    inference_accept
  elif [[ "$target" == candidate || -f "$tx/previous.inference.source" ]]; then
    serving_target=$target; inference_paths
    inference_accept
  fi
  schema > "$tx/schema.accepted"
  cp "$tx/$target.env" "$state/current.env.new"
  mv "$state/current.env.new" "$state/current.env"
  cp "$tx/$target.compose" "$state/current.compose.new"
  mv "$state/current.compose.new" "$state/current.compose"
  cp "$tx/$target.base.env" "$state/current.base.env.new"
  mv "$state/current.base.env.new" "$state/current.base.env"
  printf '%s %s\n' "$release" "$target" > "$tx/result"
  if [[ -f "$tx/serving-only" ]]; then rm -- "$operation_parent/active-operation"; fi
  rm -- "$state/pending"
  echo "Finalized $target runtime for workflow $release; business acceptance remains separate"
elif [[ "$mode" == drain ]]; then
  if [[ -f "$state/pending" ]]; then inference_operation_open; else inference_operation_begin; fi
  inference_drain
  echo 'New generations blocked and engine settled; resume or rotate-key retains the same reservation'
elif [[ "$mode" == resume ]]; then
  inference_operation_open
  [[ ! -f "$tx/rotation.started" ]] || { echo 'Use complete-rotation; bypassing credential handoff is refused' >&2; exit 1; }
  inference_resume
  echo 'Serving resumed; finish is required to verify runtime health and release the reservation'
elif [[ "$mode" == rotate-key ]]; then
  inference_operation_open
  inference_rotate "${3:?new protected key file}" "${4:?protected BYOK handoff request file}" "${5:?new secret version identifier}"
elif [[ "$mode" == complete-rotation ]]; then
  inference_operation_open
  inference_rotation_complete "${3:?protected reconciled BYOK handoff request file}"
elif [[ "$mode" == serving-rollback ]]; then
  if [[ -f "$state/pending" ]]; then
    inference_operation_open
    [[ ! -f "$tx/rotation.started" ]] || { echo 'Complete the active rotation before serving rollback' >&2; exit 1; }
  else
    inference_operation_begin
  fi
  if [[ ! -f "$tx/previous.inference.source" ]]; then
    for suffix in source env json sha256 operator.json receipt.json; do
      cp "$state/previous.inference.$suffix" "$tx/previous.inference.$suffix"
    done
  fi
  serving_target=previous; inference_compatible_restore
  serving_target=candidate; inference_drain
  serving_target=previous
  touch "$tx/serving-restored"
  inference_start; inference_resume
  echo 'Compatible serving restored with the current credential; application and database unchanged. Verify runtime health with finish.'
else
  echo 'Expected deploy, rollback, finish, drain, resume, rotate-key, complete-rotation or serving-rollback' >&2
  exit 1
fi
