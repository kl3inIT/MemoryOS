"""The pool a deployable holds, and the room the role leaves it.

Neither deployable configured HikariCP, so both ran the Spring Boot default of ten against a role
created with a limit of twenty. Staging sat at exactly 20 of 20, every one of them idle: no
connection was left for a migration, for someone looking at data during an incident, or for the
overlap while a rollout replaces a container. The sum matching the limit was arithmetic, not a
decision, and nothing here said what the numbers were for.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEPLOYMENT = ROOT / "infrastructure/deployment"

POOLS = {
    "api": ROOT / "api/src/main/resources/application.yaml",
    "worker": ROOT / "worker/src/main/resources/application.yaml",
}


def pool_setting(text, name):
    match = re.search(r"^\s+%s: \$\{[A-Z_]+:(\d+)\}\s*$" % re.escape(name), text, re.M)
    return int(match.group(1)) if match else None


class PoolSizingTest(unittest.TestCase):
    def test_every_deployable_states_its_pool_size(self):
        # A default is a number nobody chose, in a file nobody reads.
        for deployable, path in POOLS.items():
            text = path.read_text(encoding="utf-8")
            self.assertIsNotNone(pool_setting(text, "maximum-pool-size"),
                                 "%s does not state maximum-pool-size" % deployable)
            self.assertIn("pool-name: memoryos-%s" % deployable, text,
                          "%s has an unnamed pool, so its metrics cannot be told apart" % deployable)

    def test_each_pool_is_fixed_size(self):
        # HikariCP recommends a fixed pool: opening a connection is slow and would happen exactly
        # when load arrives. Idle backends are cheap on the PostgreSQL side.
        for deployable, path in POOLS.items():
            text = path.read_text(encoding="utf-8")
            self.assertEqual(pool_setting(text, "minimum-idle"),
                             pool_setting(text, "maximum-pool-size"),
                             "%s shrinks its pool, which costs latency when it is least affordable"
                             % deployable)

    def test_the_pools_together_stay_within_what_the_server_can_usefully_run(self):
        # (cores x 2) + effective spindles, for the 12 vCPU node on SSD. Beyond that the server
        # spends its time switching between backends rather than answering.
        total = sum(pool_setting(path.read_text(encoding="utf-8"), "maximum-pool-size")
                    for path in POOLS.values())
        self.assertLessEqual(total, 25, "the pools together exceed what the node can usefully run")

    def test_the_role_leaves_room_beyond_the_pools(self):
        # A role at its limit reports "too many connections for role", which reads as load rather
        # than as a limit somebody chose. The room is for a migration, an operator and a rollout.
        bootstrap = (ROOT / "infrastructure/postgres/bootstrap-shared-databases.sh").read_text(encoding="utf-8")
        match = re.search(r'MEMORYOS_DATABASE_CONNECTION_LIMIT:=(\d+)', bootstrap)
        self.assertIsNotNone(match, "the connection limit is not stated")
        limit = int(match.group(1))
        total = sum(pool_setting(path.read_text(encoding="utf-8"), "maximum-pool-size")
                    for path in POOLS.values())
        self.assertGreaterEqual(limit - total, 10,
                                "the role leaves %d connections beyond the pools" % (limit - total))
        self.assertNotIn("\n    20", bootstrap, "a bare number is back in place of the limit")

    def test_the_server_states_its_own_memory(self):
        # Every one of these was the stock default, on machines that have far more memory than the
        # defaults assume. They stay modest here because one environment shares its host.
        base = (DEPLOYMENT / "compose.base.yaml").read_text(encoding="utf-8")
        for setting in ("shared_buffers", "effective_cache_size", "work_mem",
                        "maintenance_work_mem", "max_connections"):
            self.assertIn("%s=${MEMORYOS_POSTGRES_" % setting, base, setting)

    def test_a_forgotten_transaction_is_ended(self):
        # An open transaction holds its locks and stops VACUUM reclaiming anything newer. Set on
        # the role, so a migration or an administrative session under another role is unaffected.
        sql = (ROOT / "infrastructure/postgres/bootstrap-database.sql").read_text(encoding="utf-8")
        self.assertIn("idle_in_transaction_session_timeout", sql)
        self.assertIn("ALTER ROLE %I SET idle_in_transaction_session_timeout", sql)


if __name__ == "__main__":
    unittest.main()
