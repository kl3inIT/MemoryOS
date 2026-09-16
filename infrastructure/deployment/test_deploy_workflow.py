"""Guard deployment policy independently of staging accounts and infrastructure."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/deploy-staging.yml").read_text(encoding="utf-8")
CI_WORKFLOW = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
SCRIPT = (ROOT / "infrastructure/deployment/deploy-staging.sh").read_text(encoding="utf-8")


class StagingDeploymentContractTest(unittest.TestCase):
    def test_commits_ci_ignores_do_not_make_an_automatic_release_stale(self):
        resolve = WORKFLOW.split("- name: Resolve a successful main release", 1)[1].split("- name: Download preserved release", 1)[0]
        ignored = re.compile(re.search(r"grep -Ev '([^']+)'", resolve).group(1))
        for path in ("docs/runbooks/ci-cd.md", "AGENTS.md", "tools/visual-paradigm-mcp/mcp-server/build.gradle.kts"):
            self.assertTrue(ignored.search(path), path)
        for path in ("landing/src/content/page.md", "core/src/main/java/io/memoryos/A.java", ".github/workflows/ci.yml", "openapi.yml"):
            self.assertFalse(ignored.search(path), path)
        # The filter mirrors CI's paths-ignore; a new ignored path must be added to both.
        for entry in ("'docs/**'", "'*.md'", "'tools/visual-paradigm-mcp/**'"):
            self.assertEqual(CI_WORKFLOW.count(entry), 2, entry)

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

    def test_interpreter_images_join_the_release_contract(self):
        publish = CI_WORKFLOW.split("name: Publish verified release", 1)[1].split("publish-landing:", 1)[0]
        self.assertIn("name: candidate-interpreter", CI_WORKFLOW)
        self.assertIn("docker load --input candidate/interpreter.tar", publish)
        self.assertIn("for component in api worker web interpreter interpreter-executor; do", publish)
        # Compose rejects a hyphen in an environment key.
        self.assertIn("key=${component//-/_}", publish)
        self.assertIn("images=(api worker web interpreter interpreter-executor)", SCRIPT)
        self.assertIn('[[ $(wc -l < "$tx/images.env") == 6 ]]', SCRIPT)
        deploy = SCRIPT.split('if [[ "$mode" == deploy ]]', 1)[1].split('elif [[ "$mode" == rollback ]]', 1)[0]
        # The executor is not a Compose service: pull it with the job-scoped credentials before reserving.
        self.assertLess(deploy.index("docker login ghcr.io"), deploy.index("docker pull --quiet"))
        self.assertLess(deploy.index("docker pull --quiet"), deploy.index('> "$state/pending"'))
        # A runtime accepted before the interpreter joined the release has no interpreter container.
        self.assertIn('has_interpreter "$state/current.env"', deploy)
        self.assertIn('--argjson count "${#previous_components[@]}"', deploy)

    def test_interpreter_is_reachable_only_on_the_internal_network(self):
        compose = (ROOT / "infrastructure/deployment/compose.staging.yaml").read_text(encoding="utf-8")
        service = compose.split("\n  interpreter:\n", 1)[1].split("\n  mailpit:\n", 1)[0]
        self.assertNotIn("ports:", service)
        self.assertIn("memoryos-internal:", service)
        for network in ("shared-infra", "proxy", "memoryos-telemetry"):
            self.assertNotIn(network, service)
        self.assertIn("PYTHON_EXECUTOR_DOCKER_NETWORK: none", service)


if __name__ == "__main__":
    unittest.main()
