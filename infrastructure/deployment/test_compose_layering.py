"""What both environments run belongs to the base file; an overlay carries only its own environment."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEPLOYMENT = ROOT / "infrastructure/deployment"
BASE = (DEPLOYMENT / "compose.base.yaml").read_text(encoding="utf-8")
STAGING = (DEPLOYMENT / "compose.staging.yaml").read_text(encoding="utf-8")
PRODUCTION = (DEPLOYMENT / "compose.production.yaml").read_text(encoding="utf-8")
REDIS_ENTRYPOINT = (ROOT / "infrastructure/redis/start-redis.sh").read_text(encoding="utf-8")


def defines(text, service):
    """A service is defined, not merely extended, where its own block declares an image."""
    marker = "\n  %s:\n" % service
    if marker not in text:
        return False
    rest = text.split(marker, 1)[1].split("\n")
    body = []
    for line in rest:
        if re.match(r"^[a-z]|^  [a-z0-9-]+:", line):
            break
        body.append(line)
    return any(line.strip().startswith("image:") for line in body)


def seconds(duration):
    match = re.fullmatch(r"(\d+)(ms|s)", duration)
    if not match:
        raise ValueError("Unsupported duration " + duration)
    return int(match.group(1)) / (1000 if match.group(2) == "ms" else 1)


class WorkerRedisTimeoutTest(unittest.TestCase):
    def test_the_worker_command_timeout_outlasts_its_blocking_read(self):
        # Production ran with the api's 2s for the worker too. Every idle XREADGROUP BLOCK 2000 then
        # timed out on the client, logged redis.transport.failed and backed off five seconds.
        worker = BASE.split("\n  worker:\n", 1)[1].split("\n  web:\n", 1)[0]
        timeout = re.search(r"MEMORYOS_REDIS_COMMAND_TIMEOUT: \$\{MEMORYOS_WORKER_REDIS_COMMAND_TIMEOUT:-(\w+)\}",
                            worker)
        self.assertIsNotNone(timeout, "the worker must not inherit the api's command timeout")
        application = (ROOT / "memoryos-worker/src/main/resources/application.yaml").read_text(encoding="utf-8")
        block = re.search(r"(?m)^\s+consumer-block: (\w+)$", application).group(1)
        self.assertGreater(seconds(timeout.group(1)), seconds(block))


class SignalForwardingTest(unittest.TestCase):
    def test_a_service_that_switches_user_can_still_be_stopped_gracefully(self):
        # MEM-244: docker-init runs as root with every capability dropped. A process that switched to another
        # user (SETUID) cannot be signalled without KILL: tini logged "Unexpected error when forwarding
        # signal: 'Operation not permitted'" and the Worker died mid-extraction on every stop.
        found = 0
        for name in ("compose.base.yaml", "compose.staging.yaml", "compose.production.yaml"):
            text = (DEPLOYMENT / name).read_text(encoding="utf-8")
            for block in re.findall(r"cap_add:\n((?:\s+- [A-Z_]+\n)+)", text):
                capabilities = re.findall(r"- ([A-Z_]+)", block)
                if "SETUID" in capabilities:
                    found += 1
                    self.assertIn("KILL", capabilities, name)
        self.assertGreaterEqual(found, 4)


class WorkerMemoryTest(unittest.TestCase):
    def test_the_worker_has_a_memory_limit_its_jvm_and_its_child_jvms_fit(self):
        # MEM-244: without a limit the heap could grow to 70% of the host, and each Tika child took 512 MB at start.
        worker = BASE.split("\n  worker:\n", 1)[1].split("\n  web:\n", 1)[0]
        self.assertRegex(worker, r"limits:\n\s+memory: \$\{MEMORYOS_WORKER_MEMORY_LIMIT:-4g\}")
        dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
        stage = dockerfile.split("FROM runtime AS worker", 1)[1].split("FROM runtime AS api", 1)[0]
        options = re.search(r'JAVA_TOOL_OPTIONS="([^"]+)"', stage).group(1).split()
        # The collector is named so that a changed limit cannot change it; the heap leaves half the limit to the children.
        self.assertIn("-XX:+UseSerialGC", options)
        self.assertIn("-XX:MaxRAMPercentage=50", options)
        extractor = (ROOT / "sources/src/main/java/io/memoryos/ingestion/extraction/TikaSourceContentExtractor.java"
                     ).read_text(encoding="utf-8")
        # The child inherits JAVA_TOOL_OPTIONS; its own initial size keeps it from committing its whole heap.
        self.assertIn('"-Xms32m"', extractor)

    def test_a_stopping_worker_gets_the_time_its_container_states(self):
        worker = BASE.split("\n  worker:\n", 1)[1].split("\n  web:\n", 1)[0]
        grace = int(re.search(r"stop_grace_period: (\d+)s", worker).group(1))
        deploy = (DEPLOYMENT / "deploy.sh").read_text(encoding="utf-8")
        timeouts = [int(value) for value in re.findall(r"compose stop --timeout (\d+) worker", deploy)]
        self.assertEqual([grace, grace], timeouts)
        application = (ROOT / "memoryos-worker/src/main/resources/application.yaml").read_text(encoding="utf-8")
        shutdown = seconds(re.search(r"MEMORYOS_WORKER_SHUTDOWN_GRACE:(\w+)\}", application).group(1))
        # The grace, then the rest of the shutdown: about 9 s measured, and up to 60 s for a running scheduled task.
        self.assertGreaterEqual(grace - shutdown, 70)


class WorkerAotCacheTest(unittest.TestCase):
    def test_the_cache_is_made_before_the_running_worker_stops_and_never_fails_a_deployment(self):
        # MEM-244: the training start takes about a minute; made after the stop it would be downtime.
        deploy = (DEPLOYMENT / "deploy.sh").read_text(encoding="utf-8")
        train = deploy.index("    train_worker_aot || echo")
        self.assertLess(train, deploy.index("printf '%s\\n' \"$release\" > \"$state/pending\""))
        self.assertLess(train, deploy.index("compose stop --timeout 180 worker"))
        function = deploy.split("train_worker_aot() {", 1)[1].split("\n}\n", 1)[0]
        self.assertIn("compose --profile aot run --rm --no-deps -T worker-aot", function)
        trainer = BASE.split("\n  worker-aot:\n", 1)[1].split("\n  docling:\n", 1)[0]
        # Compose starts it only when asked; it is the Worker with no stream readers, no validation of a schema
        # that is not migrated yet, and two connections.
        self.assertRegex(trainer, r"profiles:\n\s+- aot")
        self.assertRegex(trainer, r"extends:\n\s+service: worker")
        for setting in ('MEMORYOS_AOT_TRAIN: "true"', 'MEMORYOS_WORKER_ENABLED: "false"',
                        "SPRING_JPA_HIBERNATE_DDL_AUTO: none", 'MEMORYOS_DATABASE_POOL_SIZE: "2"'):
            self.assertIn(setting, trainer)

    def test_a_worker_that_reads_documents_cannot_write_the_cache_it_starts_from(self):
        # A cache is code the next start runs; only the training start, which reads no document, mounts it writable.
        worker = BASE.split("\n  worker:\n", 1)[1].split("\n  web:\n", 1)[0]
        self.assertIn("MEMORYOS_AOT_CACHE_DIR: /aot", worker)
        self.assertIn("- memoryos_worker_aot:/aot:ro", worker)
        self.assertNotRegex(worker, r"- memoryos_worker_aot:/aot\n")
        trainer = BASE.split("\n  worker-aot:\n", 1)[1].split("\n  docling:\n", 1)[0]
        self.assertRegex(trainer, r"- memoryos_worker_aot:/aot\n")
        self.assertEqual(1, BASE.count("MEMORYOS_AOT_CACHE_DIR"))


class ComposeLayeringTest(unittest.TestCase):
    def test_the_runtime_both_environments_share_is_defined_once(self):
        for service in ("redis", "interpreter", "api", "worker", "web", "postgres", "minio"):
            self.assertTrue(defines(BASE, service), service)
            self.assertFalse(defines(STAGING, service), service)
            self.assertFalse(defines(PRODUCTION, service), service)

    def test_staging_keeps_only_what_staging_runs(self):
        for inspection in ("mailpit", "pgweb", "redisinsight", "promptfoo"):
            self.assertTrue(defines(STAGING, inspection), inspection)
            self.assertNotIn(inspection, BASE, inspection)
            self.assertNotIn(inspection, PRODUCTION, inspection)

    def test_production_joins_no_shared_network(self):
        # shared-infra is external and exists only where MemoryOS shares a host with other services.
        # Leaving it in the base would make every production rollout fail on a missing network.
        self.assertNotIn("shared-infra", BASE)
        self.assertNotIn("shared-infra", PRODUCTION)
        self.assertIn("shared-infra", STAGING)
        # No host answers to the names the Keycloak once shared with OrgMemory was reached by.
        for name in ("orgmemory-keycloak", "shared-keycloak"):
            self.assertNotIn(name, BASE)
            self.assertNotIn(name, PRODUCTION)
            self.assertNotIn(name, STAGING)

    def test_a_keycloak_hostname_is_stated_rather_than_inherited(self):
        # The old default pointed at the staging realm, so forgetting the value on another host
        # silently authenticated against staging instead of failing.
        self.assertIn("KC_HOSTNAME: ${MEMORYOS_KEYCLOAK_HOSTNAME:?Set MEMORYOS_KEYCLOAK_HOSTNAME}", BASE)
        self.assertNotIn("MEMORYOS_KEYCLOAK_HOSTNAME:-", BASE)

    def test_production_search_publishes_nothing_to_inspect(self):
        staging_search = (DEPLOYMENT / "compose.search.staging.yaml").read_text(encoding="utf-8")
        production_search = (DEPLOYMENT / "compose.search.production.yaml").read_text(encoding="utf-8")
        self.assertIn("opensearch-dashboards", staging_search)
        self.assertNotIn("opensearch-dashboards", production_search)
        self.assertNotIn("shared-infra", production_search)
        # One data node cannot allocate a replica, and the health check waits for a green cluster.
        self.assertIn("MEMORYOS_SEARCH_REPLICAS must be 0", production_search)

    def test_both_deployables_trust_the_redis_authority_under_the_production_profile(self):
        # Compose enables Redis TLS for every environment, but the trust material lived only in the
        # staging profile: production alone would enable TLS and trust nothing but the system store.
        for module in ("memoryos-api", "memoryos-worker"):
            profile = (ROOT / module / "src/main/resources/application-production.yaml").read_text(encoding="utf-8")
            self.assertIn("bundle: memoryos-redis", profile, module)
            self.assertIn("${MEMORYOS_REDIS_TLS_CA_CERTIFICATE}", profile, module)

    def test_no_nested_interpolation_because_compose_versions_disagree_about_it(self):
        # ${A:-${B:?...}} reads naturally but the Compose version on the CI runner evaluates the inner
        # expression even when A is set, so a fallback to an old variable name fails the build there.
        for name, text in (("base", BASE), ("staging", STAGING), ("production", PRODUCTION)):
            self.assertNotRegex(text, r"\$\{[A-Z_]+:[-?][^}]*\$\{", name)

    def test_the_redis_entry_point_does_not_assume_an_inspector(self):
        self.assertIn('if [ -f "$MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE" ]; then', REDIS_ENTRYPOINT)
        self.assertIn('if [ -n "$INSPECTOR_HASH" ]; then', REDIS_ENTRYPOINT)
        # Staging mounts the inspector password; the base and production do not.
        self.assertIn("redis_inspector_password", STAGING)
        self.assertNotIn("redis_inspector_password", BASE)
        self.assertNotIn("redis_inspector_password", PRODUCTION)


    def test_the_object_store_and_its_client_come_from_one_registry(self):
        """A pinned digest says which bytes; it does not say who still serves them.

        Docker Hub stopped serving minio/mc, so a host that had not already cached it could not
        pull the client at all. On 2026-09-24 MinIO withdrew quay.io too, and both now come from
        this repository's own GHCR mirror, pinned by digest, which no upstream can withdraw.
        """
        base = (DEPLOYMENT / "compose.base.yaml").read_text(encoding="utf-8")
        images = re.findall(r"image: \$\{MEMORYOS_MINIO(?:_MC)?_IMAGE:-([^}]+)\}", base)
        self.assertEqual(2, len(images))
        for image in images:
            self.assertRegex(image, r"^ghcr\.io/kl3init/memoryos-minio(-mc)?:RELEASE\.[0-9TZ-]+@sha256:[0-9a-f]{64}$",
                             "MinIO comes from the repository's mirror, pinned by digest")


    def test_the_services_that_call_out_have_a_way_off_the_host(self):
        """Production's first deployment stopped on UnknownHostException for its own issuer.

        memoryos-internal and memoryos-telemetry are both internal networks: no route off the host
        and no public DNS. The api must fetch its identity provider's discovery document by the
        issuer's public name and reach the chat model; the worker must reach the embedding
        provider and every Source. Staging has shared-infra for this; production needs its own.
        """
        production = (DEPLOYMENT / "compose.production.yaml").read_text(encoding="utf-8")
        for service in ("api", "worker"):
            block = re.search(r"\n  %s:\n(.*?)(?=\n  [a-z-]+:\n|\nnetworks:)" % service, production, re.S).group(1)
            self.assertIn("      egress:", block, service)
        declared = production.split("\nnetworks:\n", 1)[1]
        egress = re.search(r"\n  egress:\n((?:    .*\n?)*)", "\n" + declared).group(1)
        self.assertNotIn("internal: true", egress, "an internal network is no way out")

if __name__ == "__main__":
    unittest.main()
