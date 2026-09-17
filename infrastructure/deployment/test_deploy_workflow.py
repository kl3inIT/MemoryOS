"""Exercise deployment finalization/recovery without a host, database or business account."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/deploy-staging.yml").read_text(encoding="utf-8")
CI_WORKFLOW = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
SCRIPT = (ROOT / "infrastructure/deployment/deploy-staging.sh").read_text(encoding="utf-8")


class StagingDeploymentPolicyTest(unittest.TestCase):
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

    def test_missing_secret_files_stop_the_deployment_before_reservation(self):
        deploy = SCRIPT.split('if [[ "$mode" == deploy ]]', 1)[1].split('elif [[ "$mode" == rollback ]]', 1)[0]
        # Compose config accepts a missing secret file; rollout would fail after the reservation.
        self.assertIn("'.secrets // {} | .[].file // empty'", deploy)
        self.assertLess(deploy.index("'.secrets // {} | .[].file // empty'"), deploy.index('> "$state/pending"'))

    def test_interpreter_and_api_share_one_key_secret(self):
        compose = (ROOT / "infrastructure/deployment/compose.staging.yaml").read_text(encoding="utf-8")
        interpreter = compose.split("\n  interpreter:\n", 1)[1].split("\n  mailpit:\n", 1)[0]
        api = compose.split("\n  api:\n", 1)[1].split("\n  worker:\n", 1)[0]
        self.assertIn("API_KEY_FILE: /run/secrets/interpreter_api_key", interpreter)
        self.assertIn("- interpreter_api_key", interpreter)
        # A 0600 operator-owned key is unreadable to capability-dropped root without DAC_OVERRIDE; the first
        # staging rollout of the key failed with PermissionError until the file was widened by hand.
        self.assertRegex(interpreter, r"cap_add:\n\s+- DAC_OVERRIDE")
        self.assertIn("MEMORYOS_INTERPRETER_API_KEY_FILE: /run/secrets/interpreter_api_key", api)
        self.assertIn("- interpreter_api_key", api)
        launcher = (ROOT / "api/src/main/docker/application-launcher.sh").read_text(encoding="utf-8")
        self.assertIn('MEMORYOS_INTERPRETER_API_KEY=$(cat "$MEMORYOS_INTERPRETER_API_KEY_FILE")', launcher)


@unittest.skipUnless(os.name == "posix" and all(shutil.which(tool) for tool in ("bash", "flock", "jq")),
                     "Deployment transactions require POSIX Bash, flock and jq")
class StagingDeploymentContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.release = "a" * 40 + "-11-1"
        self.state = self.root / "deployments"
        self.tx = self.state / self.release
        self.tx.mkdir(parents=True)
        self.pending = self.state / "pending"
        self.pending.write_text(self.release + "\n")
        self.calls = self.root / "calls.jsonl"
        self.runtime = self.root / "runtime.json"
        self.schema = self.root / "schema"
        self.schema.write_text("1|123|t\n")
        (self.tx / "schema.before").write_bytes(self.schema.read_bytes())
        (self.tx / "database.dump").write_bytes(b"operator-owned-backup")
        self.backup = (self.tx / "database.dump").read_bytes()
        (self.tx / "writers-changing").touch()
        for target, sha in (("candidate", "a" * 40), ("previous", "b" * 40)):
            (self.tx / f"{target}.env").write_text("".join(
                f"MEMORYOS_{component.upper()}_IMAGE={target}-{component}\n"
                for component in ("api", "worker", "web")) + f"MEMORYOS_RELEASE={sha}\n")
            (self.tx / f"{target}.base.env").write_text("OPERATOR_CONFIGURATION=retained\n")
            (self.tx / f"{target}.compose").write_text(str(self.root / f"{target}.yaml") + "\n")
        self.set_runtime()

        # Redirect only the installation root and privilege precondition in the
        # temporary script. All transaction/health/schema logic executes unchanged;
        # refusing an unfamiliar script prevents ever reaching /apps/memoryos.
        source = SCRIPT
        substitutions = {
            "root=/apps/memoryos": 'root="$MEMORYOS_TEST_ROOT"',
            "[[ $EUID == 0 ]]": '[[ -d "$MEMORYOS_TEST_ROOT" ]]',
        }
        for original, replacement in substitutions.items():
            if source.count(original) != 1:
                raise RuntimeError("Deployment sandbox precondition changed")
            source = source.replace(original, replacement, 1)
        self.script = self.root / "deploy-staging.sh"
        self.script.write_text(source)
        self.control = self.root / "control"
        self.control.mkdir()
        helpers = self.tx / "source/infrastructure/deployment"
        helpers.mkdir(parents=True)
        # Serving internals have their own behavioral suite. Here a failed serving
        # readiness check must prevent the application transaction from committing.
        (helpers / "inference-operations.sh").write_text('''inference_paths() {
  serving_control=$MEMORYOS_TEST_ROOT/control
}
inference_accept() {
  [[ ! -e "$serving_control/maintenance" && "$SERVING_READY" == true ]]
}
inference_compose() { docker inference-compose "$@"; }
''')
        binaries = self.root / "bin"
        binaries.mkdir()
        docker = binaries / "docker"
        docker.write_text(f"#!{sys.executable}\n" + '''import json
import os
from pathlib import Path
import sys

root = Path(os.environ["MEMORYOS_TEST_ROOT"])
args = sys.argv[1:]
with (root / "calls.jsonl").open("a") as output:
    output.write(json.dumps(args) + "\\n")
if args[:2] == ["image", "inspect"]:
    print(args[-1])
elif args[0] == "inspect":
    print(json.dumps([json.loads((root / "runtime.json").read_text())[args[1]]]))
elif args[:2] == ["exec", "memoryos-postgres"]:
    print((root / "schema").read_text(), end="")
elif args[0] in ("compose", "inference-compose"):
    pass
else:
    sys.exit("Unexpected Docker operation: " + repr(args))
''')
        docker.chmod(0o755)
        self.environment = {
            **{key: value for key, value in os.environ.items()
               if not key.startswith(("STAGING_SMOKE", "MEMORYOS_SMOKE"))},
            "PATH": str(binaries) + os.pathsep + os.environ["PATH"],
            "MEMORYOS_TEST_ROOT": str(self.root),
            "SERVING_READY": "true",
        }

    def set_runtime(self, target="candidate", unhealthy=None, wrong_revision=None):
        sha = ("a" if target == "candidate" else "b") * 40
        self.runtime.write_text(json.dumps({
            f"memoryos-{component}": {
                "Image": f"{target}-{component}",
                "State": {"Running": True, "Restarting": False,
                          "Health": {"Status": "unhealthy" if component == unhealthy else "healthy"}},
                "Config": {"Labels": {"org.opencontainers.image.revision":
                                      "c" * 40 if component == wrong_revision else sha}},
            } for component in ("api", "worker", "web")
        }))

    def operate(self, mode):
        return subprocess.run(["bash", str(self.script), mode, self.release],
                              env=self.environment, capture_output=True, text=True, timeout=10)

    def docker_calls(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_healthy_finish_commits_without_business_accounts(self):
        result = self.operate("finish")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.pending.exists())
        self.assertEqual((self.state / "current.env").read_bytes(), (self.tx / "candidate.env").read_bytes())
        self.assertEqual((self.tx / "database.dump").read_bytes(), self.backup)

    def test_failed_application_or_serving_verification_retains_reservation(self):
        for failure in ("health", "revision", "serving"):
            with self.subTest(failure=failure):
                self.set_runtime(unhealthy="worker" if failure == "health" else None,
                                 wrong_revision="web" if failure == "revision" else None)
                self.environment["SERVING_READY"] = "false" if failure == "serving" else "true"
                result = self.operate("finish")
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.pending.read_text().strip(), self.release)
                self.assertFalse((self.state / "current.env").exists())
                self.assertEqual((self.tx / "database.dump").read_bytes(), self.backup)

    def test_finish_cannot_clear_another_release_reservation(self):
        owner = "d" * 40 + "-22-1"
        self.pending.write_text(owner + "\n")
        result = self.operate("finish")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.pending.read_text().strip(), owner)
        self.assertEqual(self.docker_calls(), [])

    def test_schema_drift_stops_writers_without_restoring_images_or_database(self):
        self.schema.write_text("1|123|t\n2|456|t\n")
        result = self.operate("rollback")
        self.assertNotEqual(result.returncode, 0)
        calls = self.docker_calls()
        self.assertTrue(any("stop" in call and "api" in call and "worker" in call for call in calls))
        self.assertFalse(any("up" in call or "pg_restore" in call for call in calls))
        self.assertTrue((self.control / "maintenance").exists())
        self.assertEqual(self.pending.read_text().strip(), self.release)
        self.assertEqual((self.tx / "database.dump").read_bytes(), self.backup)

    def test_unchanged_schema_rollback_retains_reservation_until_healthy_finish(self):
        self.set_runtime(target="previous")
        result = self.operate("rollback")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.pending.read_text().strip(), self.release)
        self.assertFalse((self.state / "current.env").exists())
        self.assertTrue(any("up" in call for call in self.docker_calls()))
        result = self.operate("finish")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.pending.exists())
        self.assertEqual((self.state / "current.env").read_bytes(), (self.tx / "previous.env").read_bytes())
        self.assertFalse(any("pg_restore" in call for call in self.docker_calls()))
        self.assertEqual((self.tx / "database.dump").read_bytes(), self.backup)

    def test_application_rollback_cannot_take_over_a_serving_operation(self):
        (self.tx / "active-operation").write_text("operation-123\n")
        result = self.operate("rollback")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.pending.read_text().strip(), self.release)
        self.assertEqual((self.tx / "active-operation").read_text(), "operation-123\n")
        self.assertEqual(self.docker_calls(), [])


if __name__ == "__main__":
    unittest.main()
