#!/usr/bin/env bash
# Rolls the serving node's GPU services to the configuration of one verified release.
# Usage: deploy-serving.sh <release>
#
# CD copies the release bundle (manifest.json, configuration.tar, images.env, SHA256SUMS) and this
# script to /apps/memoryos-serving/incoming/<release>. The services use third-party images pinned
# by digest in compose.serving.yaml, so nothing here is built or signed by the release; the release
# decides which configuration runs. The firewall is applied before any port is published, because a
# published port answers the whole private subnet until DOCKER-USER says otherwise.
set -Eeuo pipefail
umask 077
release=${1:?verified SHA-workflowRun-workflowAttempt}
[[ "$release" =~ ^[0-9a-f]{40}-[1-9][0-9]*-[1-9][0-9]*$ ]]
[[ $EUID == 0 ]]
root=/apps/memoryos-serving
environment_file=$root/.env.serving
incoming=$root/incoming/$release
exec 9>"$root/lock"
flock --nonblock 9 || { echo 'Another serving operation owns the lock' >&2; exit 1; }

[[ -f "$environment_file" && ! -L "$environment_file" ]]
[[ "$(stat -c '%a' "$environment_file")" == 600 ]]
(cd "$incoming" && sha256sum --check --strict SHA256SUMS)
jq --exit-status --arg sha "${release:0:40}" '
  .repository == "kl3inIT/MemoryOS" and .sha == $sha
' "$incoming/manifest.json" > /dev/null
rm -rf "$incoming/config"
mkdir "$incoming/config"
tar --extract --file "$incoming/configuration.tar" --directory "$incoming/config" --no-same-owner --no-same-permissions
deployment=$incoming/config/infrastructure/deployment

setting() {
  sed -n "s/^$1=//p" "$environment_file" | tail -n 1
}
allowed=$(setting MEMORYOS_SERVING_ALLOWED_SOURCE)
read -r -a ports <<< "$(setting MEMORYOS_SERVING_PORTS)"
[[ "$allowed" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]
(( ${#ports[@]} > 0 ))

compose() {
  docker compose --project-name memoryos-serving --env-file "$environment_file" \
    -f "$deployment/compose.serving.yaml" "$@"
}
compose config --quiet
# Compose accepts a missing secret file and the service would then fail after the old one stopped.
# Only the path is ever printed; the key itself stays in the file.
compose config --format json | jq --raw-output '.secrets // {} | .[].file // empty' | while IFS= read -r file; do
  [[ -f "$file" && ! -L "$file" && -s "$file" ]] || { echo "Missing Compose secret file: $file" >&2; exit 1; }
done
# Every port Compose would publish must be one the firewall filters. The two come from different
# settings, and a port published outside the list answers the whole subnet without authentication.
compose config --format json | jq --exit-status --arg ports " ${ports[*]} " '
  [.services[].ports[]? | .published | tostring] | all(. as $port | $ports | contains(" " + $port + " "))
' > /dev/null || { echo 'A published port is missing from MEMORYOS_SERVING_PORTS' >&2; exit 1; }

install -m 0755 "$deployment/serving-firewall.sh" /usr/local/sbin/memoryos-serving-firewall
install -m 0644 "$deployment/systemd/memoryos-serving-firewall.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --quiet memoryos-serving-firewall.service
# Docker requires the unit, so restarting it would restart every container. The script is idempotent
# and applies the current settings directly; the unit applies them at boot, before Docker starts.
/usr/local/sbin/memoryos-serving-firewall "$allowed" "${ports[@]}"
compose pull --quiet
compose up -d --remove-orphans --wait --wait-timeout 900
compose ps --all --format json | jq --exit-status --slurp '
  length > 0 and all(.[]; .State == "running" and .Health == "healthy")
' > /dev/null
printf '%s\n' "$release" > "$root/current"
echo "Serving node runs the configuration of $release"
