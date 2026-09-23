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
        application = (ROOT / "worker/src/main/resources/application.yaml").read_text(encoding="utf-8")
        block = re.search(r"(?m)^\s+consumer-block: (\w+)$", application).group(1)
        self.assertGreater(seconds(timeout.group(1)), seconds(block))


class ComposeLayeringTest(unittest.TestCase):
    def test_the_runtime_both_environments_share_is_defined_once(self):
        for service in ("redis", "interpreter", "api", "worker", "web", "postgres", "minio"):
            self.assertTrue(defines(BASE, service), service)
            self.assertFalse(defines(STAGING, service), service)
            self.assertFalse(defines(PRODUCTION, service), service)

    def test_staging_keeps_only_what_staging_runs(self):
        for inspection in ("mailpit", "pgweb", "redisinsight"):
            self.assertTrue(defines(STAGING, inspection), inspection)
            self.assertNotIn(inspection, BASE, inspection)
            self.assertNotIn(inspection, PRODUCTION, inspection)

    def test_production_joins_no_shared_network(self):
        # shared-infra is external and exists only where MemoryOS shares a host with OrgMemory.
        # Leaving it in the base would make every production rollout fail on a missing network.
        self.assertNotIn("shared-infra", BASE)
        self.assertNotIn("shared-infra", PRODUCTION)
        self.assertIn("shared-infra", STAGING)
        # Nor does production answer to the names the shared Keycloak is reached by.
        for name in ("orgmemory-keycloak", "shared-keycloak"):
            self.assertNotIn(name, BASE)
            self.assertNotIn(name, PRODUCTION)
            self.assertIn(name, STAGING)

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
        for module in ("api", "worker"):
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
        pull the client at all, while the server it belongs to pulled from quay.io without
        trouble. The digest is unchanged: quay.io carries the same one.
        """
        base = (DEPLOYMENT / "compose.base.yaml").read_text(encoding="utf-8")
        registries = set(re.findall(r"image: \$\{MEMORYOS_MINIO(?:_MC)?_IMAGE:-([^/]+/)", base))
        self.assertEqual(registries, {"quay.io/"}, "the client and the server disagree on a source")


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
