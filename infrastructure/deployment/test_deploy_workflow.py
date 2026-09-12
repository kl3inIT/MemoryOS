"""Guard deployment policy independently of staging accounts and infrastructure."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/deploy-staging.yml").read_text(encoding="utf-8")
SCRIPT = (ROOT / "infrastructure/deployment/deploy-staging.sh").read_text(encoding="utf-8")


class StagingDeploymentContractTest(unittest.TestCase):
    def test_delivery_does_not_require_business_test_tooling_or_accounts(self):
        for removed in ("STAGING_SMOKE", "MEMORYOS_SMOKE", "test:staging", "playwright", "setup-node", "corepack", "pnpm"):
            self.assertNotIn(removed, WORKFLOW)

    def test_release_and_health_guards_are_preserved(self):
        for guard in (".event == \"push\"", ".head_branch == \"main\"", "Publish verified release", "sha256sum --check --strict", "git merge-base --is-ancestor", "StrictHostKeyChecking yes"):
            self.assertIn(guard, WORKFLOW)
        self.assertLess(WORKFLOW.index("Pull images, back up"), WORKFLOW.index("Finalize the healthy deployment"))
        for guard in ("pg_dump", "pg_restore --list", "flock --nonblock", '--no-deps --pull never --wait', '.State.Health.Status == "healthy"', '.Image == $image', 'org.opencontainers.image.revision'):
            self.assertIn(guard, SCRIPT)

    def test_failure_reports_without_automatic_rollback(self):
        report = WORKFLOW.split("- name: Report manual recovery", 1)[1].split("- name: Remove ephemeral", 1)[0]
        self.assertIn("failure() || cancelled()", report)
        self.assertNotIn("ssh ", report)
        self.assertNotIn("rm ", report)
        self.assertNotIn("deploy-staging.sh' rollback", WORKFLOW)
        self.assertIn("cancel-in-progress: false", WORKFLOW)

    def test_manual_finish_keeps_exact_selection_and_server_ownership_guard(self):
        recovery = WORKFLOW.split("- name: Finish only the explicitly selected", 1)[1].split("- name: Pull images", 1)[0]
        self.assertIn("inputs.recovery_release != ''", recovery)
        self.assertIn('[[ "$RECOVERY_RELEASE" =~ ^[0-9a-f]{40}', recovery)
        self.assertIn("finish '$RECOVERY_RELEASE'", recovery)
        finish = SCRIPT.split('elif [[ "$mode" == finish ]]', 1)[1]
        self.assertLess(finish.index('"$(cat "$state/pending")" == "$release"'), finish.index("verify_runtime"))
        self.assertLess(finish.index("verify_runtime"), finish.index('rm -- "$state/pending"'))

    def test_manual_rollback_checks_schema_before_restoring_images(self):
        rollback = SCRIPT.split('elif [[ "$mode" == rollback ]]', 1)[1].split('elif [[ "$mode" == finish ]]', 1)[0]
        self.assertLess(rollback.index('cmp --silent "$tx/schema.before"'), rollback.index('target=previous; rollout'))
        self.assertIn("exit 1", rollback)
        self.assertNotIn("pg_restore", rollback)


if __name__ == "__main__":
    unittest.main()
