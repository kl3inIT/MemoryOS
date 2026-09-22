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

    def test_a_keycloak_hostname_is_stated_rather_than_inherited(self):
        # The old default pointed at the staging realm, so forgetting the value on another host
        # silently authenticated against staging instead of failing.
        self.assertIn("KC_HOSTNAME: ${MEMORYOS_KEYCLOAK_HOSTNAME:?Set MEMORYOS_KEYCLOAK_HOSTNAME}", BASE)
        self.assertNotIn("MEMORYOS_KEYCLOAK_HOSTNAME:-", BASE)

    def test_the_redis_entry_point_does_not_assume_an_inspector(self):
        self.assertIn('if [ -f "$MEMORYOS_REDIS_INSPECTOR_PASSWORD_FILE" ]; then', REDIS_ENTRYPOINT)
        self.assertIn('if [ -n "$INSPECTOR_HASH" ]; then', REDIS_ENTRYPOINT)
        # Staging mounts the inspector password; the base and production do not.
        self.assertIn("redis_inspector_password", STAGING)
        self.assertNotIn("redis_inspector_password", BASE)
        self.assertNotIn("redis_inspector_password", PRODUCTION)


if __name__ == "__main__":
    unittest.main()
