#!/usr/bin/env bash
# Sourced by the existing locked deployment transaction. No independent lock or scheduler.
: "${tx:?}" "${root:?}" "${state:?}" "${release:?}"

inference_compose() {
  local source
  source=$(cat "$tx/$serving_target.inference.source")
  MEMORYOS_INFERENCE_API_KEY_FILE="${serving_key_override:-$(jq -r '.services.vllm.volumes[] | select(.target == "/run/secrets/inference_api_key") | .source' "$tx/$serving_target.inference.json")}" \
    timeout "${inference_command_timeout:-1900}" docker compose --project-name memoryos-inference --env-file "$tx/$serving_target.inference.env" \
    -f "$source/infrastructure/deployment/compose.inference.yaml" "$@"
}

inference_paths() {
  serving_source=$(cat "$tx/$serving_target.inference.source")
  serving_manifest=$serving_source/infrastructure/inference/managed/manifest.json
  serving_control=$(jq -er '.services["inference-gateway"].volumes[] | select(.target == "/var/lib/memoryos-inference/control") | .source' "$tx/$serving_target.inference.json")
  serving_key=$(jq -er '.services.vllm.volumes[] | select(.target == "/run/secrets/inference_api_key") | .source' "$tx/$serving_target.inference.json")
  serving_assets=$(jq -er '.services.vllm.volumes[] | select(.target == "/var/lib/memoryos-inference/assets") | .source' "$tx/$serving_target.inference.json")
  serving_cache=$(jq -er '.services.vllm.volumes[] | select(.target == "/var/cache/memoryos-inference") | .source' "$tx/$serving_target.inference.json")
  serving_startup=$(jq -er '.acceptanceThresholds.coldStartSeconds' "$serving_manifest")
  [[ "$serving_startup" =~ ^[1-9][0-9]*$ ]] && (( serving_startup <= 1800 ))
}

inference_probe() {
  local mode=$1 base=http://inference-gateway:8080 allowance=${2:-15}
  if [[ "$mode" == idle ]]; then base=http://127.0.0.1:8000; fi
  if [[ "$mode" == generation ]]; then allowance=$(jq -r '.acceptanceThresholds.generationE2EMaxSeconds' "$serving_manifest"); fi
  [[ "$allowance" =~ ^[1-9][0-9]*$ ]] && (( allowance <= 180 ))
  inference_command_timeout=$((allowance + 5)) inference_probe_exec "$mode" "$base" "$allowance"
}

inference_probe_exec() {
  local mode=$1 base=$2 allowance=$3
  inference_compose exec -T vllm /opt/venv/bin/python3 /opt/memoryos-inference/probe.py \
    --manifest /opt/memoryos-inference/manifest.json --base-url "$base" \
    --key-file /run/secrets/inference_api_key --mode "$mode" --timeout "$allowance"
}

inference_verify_artifacts() {
  local source=$1 sums=$2 expected=$3
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]]
  [[ "$(sha256sum "$source/infrastructure/inference/managed/manifest.json" | cut -d ' ' -f 1)" == "$expected" ]]
  (cd "$source" && sha256sum --check --strict "$sums") > /dev/null
}

inference_verify_images() {
  local role service reference platform image container
  for role in engine gateway; do
    service=vllm; if [[ "$role" == gateway ]]; then service=inference-gateway; fi
    reference=$(jq -er --arg role "$role" '.images[$role].reference' "$serving_manifest")
    platform=$(jq -er --arg role "$role" '.images[$role].platform' "$serving_manifest")
    [[ "$reference" =~ @sha256:[0-9a-f]{64}$ && "$platform" == linux/amd64 ]]
    [[ "$(jq -r --arg service "$service" '.services[$service].image' "$tx/$serving_target.inference.json")" == "$reference" ]]
    docker image inspect "$reference" | jq -e --arg digest "${reference##*@}" '
      .[0] | .Os == "linux" and .Architecture == "amd64"
      and any(.RepoDigests[]; endswith("@" + $digest))' > /dev/null
    if [[ "${1:-}" == running ]]; then
      image=$(docker image inspect --format '{{.Id}}' "$reference")
      container=$(inference_compose ps --quiet "$service")
      [[ -n "$container" ]]
      docker inspect "$container" | jq -e --arg image "$image" '
        .[0] | .Image == $image and .State.Running and (.State.Restarting | not)
        and .State.Health.Status == "healthy"' > /dev/null
    fi
  done
}

inference_prepare() {
  [[ -f "$root/inference.env" && ! -L "$root/inference.env" && "$(stat -c '%a' "$root/inference.env")" == 600 ]]
  [[ -f "$root/inference-operator.json" && ! -L "$root/inference-operator.json" ]]
  cp "$root/inference.env" "$tx/candidate.inference.env"
  cp "$root/inference-operator.json" "$tx/candidate.inference.operator.json"
  printf '%s\n' "$tx/source" > "$tx/candidate.inference.source"
  inference_verify_artifacts "$tx/source" "$tx/serving.sha256" "$(jq -er '.servingManifestSha256' "$tx/manifest.json")"
  cp "$tx/serving.sha256" "$tx/candidate.inference.sha256"
  serving_target=candidate
  # Initial expansion has no pre-existing resolved configuration.
  docker compose --project-name memoryos-inference --env-file "$tx/candidate.inference.env" \
    -f "$tx/source/infrastructure/deployment/compose.inference.yaml" config --format json > "$tx/candidate.inference.json"
  inference_paths
  printf 'MEMORYOS_INFERENCE_CLIENT_NETWORK=%s\n' "$(jq -er '.networks["memoryos-inference-client"].name' "$tx/candidate.inference.json")" >> "$tx/candidate.env"
  jq -e --arg hash "$(jq -r '.servingManifestSha256' "$tx/manifest.json")" '
    .environment == "staging" and .topology == "single-host"
    and (.operator | type == "string" and length > 0)
    and .servingManifestSha256 == $hash
    and (.credentialVersion | type == "string" and test("^[A-Za-z0-9._-]{1,128}$"))
    and (.capacityEvidenceFile | type == "string" and startswith("/apps/memoryos/"))
    and (.capacityEvidenceSha256 | test("^[0-9a-f]{64}$"))
  ' "$tx/candidate.inference.operator.json" > /dev/null
  local evidence
  evidence=$(jq -r '.capacityEvidenceFile' "$tx/candidate.inference.operator.json")
  [[ -f "$evidence" && ! -L "$evidence" ]]
  [[ "$(sha256sum "$evidence" | cut -d ' ' -f 1)" == "$(jq -r '.capacityEvidenceSha256' "$tx/candidate.inference.operator.json")" ]]
  # The operator receipt owns physical backing-disk/co-load qualification; Linux checks are additional.
  [[ "$(uname -m)" == x86_64 ]]
  local flags required
  flags=$(sed -n 's/^flags[[:space:]]*:[[:space:]]*/ /p' /proc/cpuinfo)
  while IFS= read -r required; do
    [[ " $flags " == *" $required "* ]] || { echo 'Host lacks a required inference CPU feature' >&2; return 1; }
  done < <(jq -r '.hostRequirements.minimumCpuFlags[]' "$serving_manifest")
  local memory floor docker_root directory available
  memory=$(sed -n 's/^MemAvailable:[[:space:]]*\([0-9]*\) kB$/\1/p' /proc/meminfo)
  floor=$(jq -r '.hostRequirements.minimumAvailableMemoryBeforeStartBytes' "$serving_manifest")
  (( memory * 1024 >= floor ))
  docker_root=$(docker info --format '{{.DockerRootDir}}')
  for directory in "$serving_assets" "$serving_cache" "$docker_root"; do
    [[ -d "$directory" && ! -L "$directory" ]]
    available=$(df --output=avail --block-size=1 "$directory" | tail -n 1)
    (( available >= $(jq -r '.hostRequirements.minimumFreeDiskBeforeProvisioningBytes' "$serving_manifest") ))
  done
  [[ -d "$serving_control" && ! -L "$serving_control" && "$(stat -c '%a' "$serving_control")" == 755 ]]
  [[ -f "$serving_key" && ! -L "$serving_key" && "$(stat -c '%a:%u:%g' "$serving_key")" == 400:1654:1654 ]]
  [[ "$(stat -c '%a:%u:%g' "$serving_cache")" == 700:1654:1654 ]]
  python3 "$serving_source/infrastructure/deployment/inference-credential.py" same --key-file "$serving_key" --other-key-file "$serving_key"
  python3 "$serving_source/infrastructure/inference/managed/provision.py" --manifest "$serving_manifest" --assets-root "$serving_assets"
  inference_compose pull vllm inference-gateway
  inference_verify_images
  if [[ -f "$state/current.inference.source" ]]; then
    local suffix
    for suffix in source env json sha256 operator.json receipt.json; do
      cp "$state/current.inference.$suffix" "$tx/previous.inference.$suffix"
    done
    serving_target=previous; inference_paths
    inference_verify_artifacts "$serving_source" "$tx/previous.inference.sha256" "$(jq -er '.manifestSha256' "$tx/previous.inference.receipt.json")"
    python3 "$serving_source/infrastructure/inference/managed/provision.py" --manifest "$serving_manifest" --assets-root "$serving_assets" --verify-only
    inference_verify_images running
    serving_target=candidate; inference_paths
    [[ "$serving_control" == "$(jq -r '.services["inference-gateway"].volumes[] | select(.target == "/var/lib/memoryos-inference/control") | .source' "$tx/previous.inference.json")" ]]
    [[ "$serving_key" == "$(jq -r '.keyFile' "$state/current.inference.receipt.json")" ]]
    [[ "$(jq -r '.credentialVersion' "$tx/candidate.inference.operator.json")" == "$(jq -r '.credentialVersion' "$state/current.inference.receipt.json")" ]] || {
      echo 'Credential version changed outside the bounded rotation command' >&2; return 1;
    }
  elif [[ -n "$(inference_compose ps --all --quiet)" ]]; then
    echo 'Unreceipted serving runtime exists; explicit operator reconciliation is required' >&2; return 1
  fi
  [[ -f "$root/observability.env" && ! -L "$root/observability.env" && "$(stat -c '%a' "$root/observability.env")" == 600 ]]
  cp "$root/observability.env" "$tx/monitoring.env"
  monitoring_compose config --quiet
}

inference_gateway_settled() {
  local container snapshot
  container=$(inference_command_timeout=5 inference_compose ps --all --quiet inference-gateway) || return 1
  [[ -n "$container" ]] || return 1
  snapshot=$(timeout 5 docker inspect "$container") || return 1
  if jq -e '.[0].State | .Running == false and .Restarting == false' <<< "$snapshot" > /dev/null; then
    return 0
  fi
  jq -e '.[0].State | .Running == true and .Restarting == false' <<< "$snapshot" > /dev/null || return 1
  # Native metrics cannot see a request still uploading after rewrite admission.
  # Inspect the pinned Alpine image's TCP tables without a new endpoint or privilege.
  # Count even model/health/keepalive sockets conservatively, plus upstream sockets
  # after client disconnect. Only listeners and TIME_WAIT cannot carry later work.
  inference_command_timeout=5 inference_compose exec -T inference-gateway /bin/busybox awk '
    FNR == 1 {
      files++
      if ($2 != "local_address") invalid = 1
      next
    }
    NF < 4 { invalid = 1; next }
    {
      local_address = toupper($2)
      remote_address = toupper($3)
      state = toupper($4)
      if (local_address ~ /:1F90$/ && state == "0A") listener = 1
      if ((local_address ~ /:1F90$/ || remote_address ~ /:1F40$/) &&
          state != "0A" && state != "06") busy = 1
    }
    END { exit (invalid || files != 2 || !listener || busy) ? 1 : 0 }
  ' /proc/net/tcp /proc/net/tcp6
}

inference_drain() {
  inference_paths
  local previously_drained=false
  if [[ -f "$serving_control/maintenance" && -f "$tx/inference.drained" ]]; then
    previously_drained=true
  fi
  # A still-closed prior receipt remains a recovery witness if this attempt fails.
  # Otherwise it is stale and must not certify a stopped engine.
  if [[ "$previously_drained" == false ]]; then rm -f -- "$tx/inference.drained"; fi
  touch "$serving_control/maintenance"
  chmod 644 "$serving_control/maintenance"
  local end=$((SECONDS + 150)) remaining container
  while (( end - SECONDS > 15 )); do
    if inference_gateway_settled; then
      # A stopped engine is safe only after an earlier proved drain with admission
      # continuously closed; gateway settlement must still be proved on recovery.
      if [[ "$previously_drained" == true ]] && (( end - SECONDS > 10 )); then
        container=$(inference_command_timeout=5 inference_compose ps --all --quiet vllm) || return 1
        if [[ -n "$container" ]] && timeout 5 docker inspect "$container" |
            jq -e '.[0].State | .Running == false and .Restarting == false' > /dev/null &&
            (( SECONDS < end )); then
          date --utc +%FT%TZ > "$tx/inference.drained"
          return 0
        fi
      fi
      remaining=$((end - SECONDS - 5))
      if (( remaining <= 8 )); then break; fi
      if (( remaining > 15 )); then remaining=15; fi
      if inference_probe idle "$remaining" && (( SECONDS < end )); then
        date --utc +%FT%TZ > "$tx/inference.drained"
        return 0
      fi
    fi
    sleep 1
  done
  echo 'Drain deadline exceeded; admission stays blocked. Explicitly Stop/settle turns, then retry; no forced hidden cancellation.' >&2
  return 1
}

inference_start() {
  inference_paths
  inference_compose up -d --no-deps --pull never --force-recreate --wait --wait-timeout "$serving_startup" vllm inference-gateway
  inference_verify_images running
  inference_probe ready
}

inference_resume() {
  inference_paths
  inference_probe ready
  # Opening admission invalidates settlement even if the subsequent smoke fails.
  rm -f -- "$tx/inference.drained"
  rm -f -- "$serving_control/maintenance"
  if ! inference_probe generation > "$tx/$serving_target.inference.generation.json"; then
    touch "$serving_control/maintenance"; chmod 644 "$serving_control/maintenance"
    echo 'Generation smoke failed; admission blocked for recovery' >&2; return 1
  fi
}

monitoring_compose() {
  local source backend_network
  source=$(cat "$tx/$serving_target.inference.source")
  backend_network=$(jq -er '.networks["memoryos-inference-backend"].name' "$tx/$serving_target.inference.json") || return 1
  MEMORYOS_INFERENCE_BACKEND_NETWORK="$backend_network" \
    timeout 150 docker compose --project-name memoryos-observability --env-file "$tx/monitoring.env" \
    -f "$source/infrastructure/observability/compose.observability.yaml" \
    -f "$source/infrastructure/observability/compose.inference.yaml" "$@"
}

inference_monitoring_apply() {
  # Independent telemetry project, only changed configuration consumers; no storage restoration.
  monitoring_compose up -d --no-deps --force-recreate --wait --wait-timeout 120 prometheus grafana
  local end=$((SECONDS + 120))
  while (( SECONDS < end )); do
    if monitoring_compose exec -T prometheus wget -T 5 -q -O - \
      'http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22memoryos-inference%22%7D' \
      | jq -e '.status == "success" and (.data.result | length == 1) and .data.result[0].value[1] == "1"' > /dev/null \
      && monitoring_compose exec -T prometheus wget -T 5 -q -O - \
      'http://localhost:9090/api/v1/query?query=count(%7B__name__%3D~%22vllm%3A(num_requests_running%7Cnum_requests_waiting%7Ckv_cache_usage_perc)%22%2Cjob%3D%22memoryos-inference%22%7D)' \
      | jq -e '.status == "success" and (.data.result | length == 1) and .data.result[0].value[1] == "3"' > /dev/null \
      && monitoring_compose exec -T prometheus wget -T 5 -q -O - 'http://localhost:9090/api/v1/rules' \
      | jq -e '.status == "success" and any(.data.groups[]; .name == "memoryos-inference" and (.rules | length == 6) and all(.rules[]; .health == "ok"))' > /dev/null; then
      jq -n --arg release "$release" --arg operator "$(jq -r '.operator' "$tx/$serving_target.inference.operator.json")" \
        --arg source "$serving_source" --arg at "$(date --utc +%FT%TZ)" \
        '{release:$release,environment:"staging",operator:$operator,source:$source,checkedAt:$at,nativeScrape:true,rulesHealthy:true,paging:false,hostExporter:false,gatewayLogIngestion:false}' \
        > "$tx/$serving_target.inference.monitoring.json"
      return 0
    fi
    sleep 5
  done
  echo 'Independent monitoring apply/check failed; no inference health dependency was added' >&2
  return 1
}

inference_receipt() {
  inference_paths
  jq -n --arg manifest "$(sha256sum "$serving_manifest" | cut -d ' ' -f 1)" \
    --arg artifacts "$(sha256sum "$tx/$serving_target.inference.sha256" | cut -d ' ' -f 1)" \
    --arg release "$release" --arg key "$serving_key" --arg at "$(date --utc +%FT%TZ)" \
    --slurpfile operator "$tx/$serving_target.inference.operator.json" \
    --slurpfile monitoring "$tx/$serving_target.inference.monitoring.json" \
    --slurpfile generation "$tx/$serving_target.inference.generation.json" \
    '{release:$release,manifestSha256:$manifest,artifactsSha256:$artifacts,keyFile:$key,credentialVersion:$operator[0].credentialVersion,operator:$operator[0].operator,environment:$operator[0].environment,checkedAt:$at,generation:$generation[0],monitoring:$monitoring[0]}' \
    > "$tx/$serving_target.inference.receipt.json"
}

inference_accept() {
  local suffix
  inference_verify_images running
  inference_probe ready
  [[ ! -e "$serving_control/maintenance" ]]
  inference_receipt
  if [[ -f "$state/current.inference.source" ]] && ! cmp --silent "$state/current.inference.source" "$tx/$serving_target.inference.source"; then
    for suffix in source env json sha256 operator.json receipt.json; do
      cp "$state/current.inference.$suffix" "$state/previous.inference.$suffix"
    done
  fi
  for suffix in source env json sha256 operator.json receipt.json; do
    cp "$tx/$serving_target.inference.$suffix" "$state/current.inference.$suffix.new"
    mv "$state/current.inference.$suffix.new" "$state/current.inference.$suffix"
  done
}

inference_compatible_restore() {
  local active_source previous_manifest current_manifest current_key current_version profile supported
  local network current_network previous_network
  active_source=$(cat "$state/current.inference.source")
  current_manifest=$active_source/infrastructure/inference/managed/manifest.json
  current_key=$(jq -er '.keyFile' "$state/current.inference.receipt.json")
  current_version=$(jq -er '.credentialVersion' "$state/current.inference.receipt.json")
  inference_paths
  previous_manifest=$serving_manifest
  profile=$(jq -er '.model.tokenizerProfile' "$previous_manifest")
  supported=$(docker inspect memoryos-api | jq -er '.[0].Config.Labels["io.memoryos.chat.tokenizer-profiles"]')
  [[ ",$supported," == *",$profile,"* ]]
  # No catalog model rewrite: restored serving must accept the existing bound model/limits.
  cmp --silent <(jq -S '{model:.model.servedModelName,profile:.model.tokenizerProfile,context:.provisionalRuntime.contextTokens,output:.provisionalRuntime.maxOutputTokens}' "$current_manifest") \
    <(jq -S '{model:.model.servedModelName,profile:.model.tokenizerProfile,context:.provisionalRuntime.contextTokens,output:.provisionalRuntime.maxOutputTokens}' "$previous_manifest") || {
    echo 'Previous serving is incompatible with the current catalog contract' >&2; return 1;
  }
  # Serving-only recovery leaves the application and telemetry attachments unchanged.
  for network in memoryos-inference-client memoryos-inference-backend; do
    current_network=$(jq -er --arg network "$network" '.networks[$network].name' "$state/current.inference.json") || return 1
    previous_network=$(jq -er --arg network "$network" '.networks[$network].name' "$tx/$serving_target.inference.json") || return 1
    [[ "$current_network" == "$previous_network" ]] || {
      echo 'Previous serving uses incompatible private networks; application and monitoring were not changed' >&2; return 1;
    }
  done
  inference_verify_artifacts "$serving_source" "$tx/$serving_target.inference.sha256" "$(jq -er '.manifestSha256' "$tx/$serving_target.inference.receipt.json")"
  python3 "$serving_source/infrastructure/inference/managed/provision.py" --manifest "$serving_manifest" --assets-root "$serving_assets" --verify-only
  # Reuse only the CURRENT credential, never restore an old/revoked key or its inode.
  serving_key_override=$current_key
  inference_compose config --format json > "$tx/$serving_target.inference.json.new"
  mv "$tx/$serving_target.inference.json.new" "$tx/$serving_target.inference.json"
  printf '\nMEMORYOS_INFERENCE_API_KEY_FILE=%s\n' "$current_key" >> "$tx/$serving_target.inference.env"
  jq --arg version "$current_version" '.credentialVersion = $version' "$tx/$serving_target.inference.operator.json" > "$tx/operator.new"
  mv "$tx/operator.new" "$tx/$serving_target.inference.operator.json"
  inference_paths
  inference_verify_images
}

inference_operation_begin() {
  [[ ! -e "$state/pending" && -f "$state/current.inference.receipt.json" ]]
  [[ "$(sed -n 's/^MEMORYOS_RELEASE=//p' "$state/current.env")" == "${release:0:40}" ]]
  local parent=$tx operation suffix
  operation=operation-$(date +%s)
  [[ ! -e "$parent/active-operation" ]]
  mkdir "$parent/$operation"
  tx=$parent/$operation
  for suffix in env compose base.env; do cp "$state/current.$suffix" "$tx/candidate.$suffix"; done
  for suffix in source env json sha256 operator.json receipt.json; do
    cp "$state/current.inference.$suffix" "$tx/candidate.inference.$suffix"
  done
  cp "$root/observability.env" "$tx/monitoring.env"
  serving_target=candidate; inference_paths
  inference_verify_artifacts "$serving_source" "$tx/candidate.inference.sha256" "$(jq -er '.manifestSha256' "$tx/candidate.inference.receipt.json")"
  schema > "$tx/schema.before"
  touch "$tx/serving-only"
  printf '%s\n' "$operation" > "$parent/active-operation"
  printf '%s\n' "$release" > "$state/pending"
}

inference_operation_open() {
  [[ -f "$state/pending" && "$(cat "$state/pending")" == "$release" ]]
  local operation
  operation=$(cat "$tx/active-operation")
  [[ "$operation" =~ ^operation-[0-9]+$ ]]
  tx=$tx/$operation
  [[ -f "$tx/serving-only" ]]
  serving_target=candidate
  if [[ -f "$tx/serving-restored" ]]; then serving_target=previous; fi
  inference_paths
}

inference_same_key() {
  python3 "$serving_source/infrastructure/deployment/inference-credential.py" same --key-file "$1" --other-key-file "$2"
}

inference_rotate() {
  local new_key=$1 request=$2 version=$3
  [[ "$version" =~ ^[A-Za-z0-9._-]{1,128}$ ]]
  [[ "$version" != "$(jq -r '.credentialVersion' "$tx/$serving_target.inference.operator.json")" ]]
  [[ ! -f "$tx/rotation.started" ]]
  # Validate all protected inputs before installing or changing server credentials.
  [[ -f "$request" && ! -L "$request" && "$(stat -c '%a' "$request")" == 600 ]]
  python3 "$serving_source/infrastructure/deployment/inference-credential.py" preflight --request "$request" --key-file "$new_key"
  [[ ! "$new_key" -ef "$serving_key" ]] && ! inference_same_key "$new_key" "$serving_key"
  inference_drain
  cp "$serving_key" "$tx/rotation.old-key"; chmod 400 "$tx/rotation.old-key"
  cp "$new_key" "$tx/rotation.new-key"; chmod 400 "$tx/rotation.new-key"
  printf '%s\n' "$version" > "$tx/rotation.version"
  touch "$tx/rotation.started"
  inference_rotation_complete "$request"
}

inference_rotation_complete() {
  local request=$1
  [[ -f "$tx/rotation.started" && ! -f "$tx/rotation.completed" ]]
  [[ -f "$serving_control/maintenance" ]]
  if ! inference_same_key "$tx/rotation.new-key" "$serving_key"; then
    inference_same_key "$tx/rotation.old-key" "$serving_key" || {
      echo 'Key changed outside this rotation; explicit operator reconciliation required' >&2; return 1;
    }
    python3 "$serving_source/infrastructure/deployment/inference-credential.py" install \
      --new-key-file "$tx/rotation.new-key" --key-file "$serving_key"
  fi
  touch "$tx/rotation.installed"
  # Recreating both processes is mandatory: atomic file replacement alone leaves stale bind inodes.
  inference_start
  if [[ ! -f "$tx/rotation.handoff.json" ]]; then
    python3 "$serving_source/infrastructure/deployment/inference-credential.py" handoff \
      --request "$request" --key-file "$serving_key" > "$tx/rotation.handoff.json.new"
    mv "$tx/rotation.handoff.json.new" "$tx/rotation.handoff.json"
  fi
  python3 "$serving_source/infrastructure/deployment/inference-credential.py" verify \
    --request "$request" --receipt "$tx/rotation.handoff.json"
  # Protected stdin carries the retired key. Never put it into Docker exec argv/environment.
  inference_command_timeout=20 inference_compose exec -T vllm /opt/venv/bin/python3 -c '
import sys, urllib.request, urllib.error
key = sys.stdin.read(66).strip()
if len(key) != 64: sys.exit(1)
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args): raise ValueError("redirect refused")
opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
for base in ("http://127.0.0.1:8000", "http://inference-gateway:8080"):
    request = urllib.request.Request(base + "/v1/models", headers={"Authorization": "Bearer " + key})
    try:
        with opener.open(request, timeout=5): sys.exit(1)
    except urllib.error.HTTPError as error:
        if error.code != 401: sys.exit(1)
' < "$tx/rotation.old-key"
  touch "$tx/rotation.old-rejected"
  jq --arg version "$(cat "$tx/rotation.version")" '.credentialVersion = $version' \
    "$tx/$serving_target.inference.operator.json" > "$tx/operator.new"
  mv "$tx/operator.new" "$tx/$serving_target.inference.operator.json"
  inference_resume
  touch "$tx/rotation.completed"
  rm -- "$tx/rotation.old-key" "$tx/rotation.new-key"
  echo 'Server key remounted, BYOK revision checked, new key accepted and old key rejected. Finish verifies runtime health and releases the reservation.'
}
