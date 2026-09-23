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
WORKFLOW = (ROOT / ".github/workflows/deploy.yml").read_text(encoding="utf-8")
STAGING_CALLER = (ROOT / ".github/workflows/deploy-staging.yml").read_text(encoding="utf-8")
PRODUCTION_CALLER = (ROOT / ".github/workflows/deploy-production.yml").read_text(encoding="utf-8")
CALLERS = (STAGING_CALLER, PRODUCTION_CALLER)
CI_WORKFLOW = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
SCRIPT = (ROOT / "infrastructure/deployment/deploy.sh").read_text(encoding="utf-8")


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
            for text in (WORKFLOW, *CALLERS):
                self.assertNotIn(removed, text, removed)

    def test_release_and_health_guards_are_preserved(self):
        for guard in (".event == \"push\"", ".head_branch == \"main\"", "Publish verified release", "sha256sum --check --strict", "git merge-base --is-ancestor", "StrictHostKeyChecking yes"):
            self.assertIn(guard, WORKFLOW)
        self.assertLess(
            WORKFLOW.index("deploy '$RELEASE' '$DEPLOY_ENVIRONMENT' '$GITHUB_ACTOR'"),
            WORKFLOW.index("finish '$RELEASE'"),
        )
        for guard in ("pg_dump", "pg_restore --list", "flock --nonblock", '--no-deps --pull never --wait', '.State.Health.Status == "healthy"', '.Image == $image', 'org.opencontainers.image.revision'):
            self.assertIn(guard, SCRIPT)

    def test_failure_reports_without_automatic_rollback(self):
        report = WORKFLOW.split("- name: Report manual recovery", 1)[1].split("- name: Remove ephemeral", 1)[0]
        self.assertIn("failure() || cancelled()", report)
        self.assertNotIn("ssh ", report)
        self.assertNotIn("rm ", report)
        self.assertNotIn("deploy.sh' rollback", WORKFLOW)
        for caller in CALLERS:
            self.assertIn("cancel-in-progress: false", caller)

    def test_manual_finish_keeps_exact_selection_and_server_ownership_guard(self):
        recovery_start = WORKFLOW.index("- name: Finish only the explicitly selected healthy recovery")
        recovery = WORKFLOW[recovery_start:WORKFLOW.index("- name:", recovery_start + 1)]
        self.assertIn("inputs.recovery_release != ''", recovery)
        self.assertIn('[[ "$RECOVERY_RELEASE" =~ ^[0-9a-f]{40}', recovery)
        self.assertIn("finish '$RECOVERY_RELEASE'", recovery)
        finish = SCRIPT.split('elif [[ "$mode" == finish ]]', 1)[1]
        self.assertLess(finish.index('"$(cat "$state/pending")" == "$release"'), finish.index("verify_runtime"))
        self.assertLess(finish.index("verify_runtime"), finish.index('rm -- "$state/pending"'))

    def test_environment_selects_configuration_instead_of_being_hardcoded(self):
        # One script serves both environments; forking it would let the two drift apart.
        self.assertIn('environment=${3:?staging or production}', SCRIPT)
        self.assertIn('[[ "$environment" =~ ^(staging|production)$ ]]', SCRIPT)
        self.assertIn('environment_file=$root/.env.$environment', SCRIPT)
        self.assertIn('compose.base.yaml "compose.$environment.yaml" "compose.search.$environment.yaml"', SCRIPT)
        for hardcoded in (".env.staging", "compose.staging.yaml", "compose.search.staging.yaml"):
            self.assertNotIn(hardcoded, SCRIPT, hardcoded)
        # The registry user moved behind the environment; a stale caller must not be read as one.
        self.assertIn('docker login ghcr.io --username "${4:?registry user}"', SCRIPT)

    def test_every_server_invocation_names_its_environment(self):
        for call in ("deploy '$RELEASE' '$DEPLOY_ENVIRONMENT'", "finish '$RELEASE' '$DEPLOY_ENVIRONMENT'", "finish '$RECOVERY_RELEASE' '$DEPLOY_ENVIRONMENT'"):
            self.assertIn(call, WORKFLOW, call)
        self.assertIn('[[ "$DEPLOY_ENVIRONMENT" =~ ^(staging|production)$ ]]', WORKFLOW)

    def test_a_release_predating_the_rename_can_only_reach_staging(self):
        # Its script resolves staging paths and takes no environment argument, so production must
        # select a newer release rather than silently deploy staging configuration.
        rollout = WORKFLOW.split("- name: Back up, migrate and wait for readiness", 1)[1].split("- name: Finalize", 1)[0]
        fallback = 'cp "${script%/*}/deploy-staging.sh" release/deploy.sh'
        self.assertIn(fallback, rollout)
        self.assertIn('[[ "$DEPLOY_ENVIRONMENT" == staging ]]', rollout)
        self.assertLess(rollout.index('[[ "$DEPLOY_ENVIRONMENT" == staging ]]'), rollout.index(fallback))
        self.assertIn("""call="deploy '$RELEASE' '$GITHUB_ACTOR'\"""", rollout)

    def test_both_environments_share_one_delivery_workflow(self):
        # Forking the delivery logic per environment is what this split exists to prevent.
        for caller in CALLERS:
            self.assertIn("uses: ./.github/workflows/deploy.yml", caller)
            self.assertNotIn("sudo -n bash", caller)
            self.assertNotIn("sha256sum --check", caller)
        self.assertIn("environment: staging", STAGING_CALLER)
        self.assertIn("environment: production", PRODUCTION_CALLER)
        self.assertIn("group: memoryos-staging", STAGING_CALLER)
        self.assertIn("group: memoryos-production", PRODUCTION_CALLER)
        self.assertIn("environment: ${{ inputs.environment }}", WORKFLOW)

    def test_production_is_never_promoted_automatically(self):
        # Read past the comments: only what the workflow executes decides whether it can self-trigger.
        executable = "\n".join(line for line in PRODUCTION_CALLER.splitlines() if not line.lstrip().startswith("#"))
        self.assertNotIn("workflow_run", executable)
        self.assertNotIn("AUTO_DEPLOY", executable)
        self.assertIn("workflow_dispatch", PRODUCTION_CALLER)
        self.assertIn("github.ref == 'refs/heads/main'", PRODUCTION_CALLER)
        # Staging keeps its existing automatic promotion.
        self.assertIn("vars.STAGING_AUTO_DEPLOY == 'true'", STAGING_CALLER)

    def test_each_environment_carries_its_own_identity_and_trust(self):
        # Environment-scoped values are read inside the job that enters the environment. A caller
        # cannot read them: `with:` is evaluated before any environment is entered and yields empty
        # strings, which is how the first run of the split failed.
        for prefix in ("STAGING", "PRODUCTION"):
            for name in ("HOST", "USER", "KNOWN_HOSTS"):
                self.assertIn("vars.%s_%s" % (prefix, name), WORKFLOW)
            self.assertIn("secrets.%s_SSH_KEY" % prefix, WORKFLOW)
        for caller in CALLERS:
            self.assertNotIn("vars.STAGING_HOST", caller)
            self.assertNotIn("vars.PRODUCTION_HOST", caller)
            self.assertNotIn("SSH_KEY", caller)
        self.assertIn("inputs.environment == 'production' && vars.PRODUCTION_HOST || vars.STAGING_HOST", WORKFLOW)

    def test_manual_rollback_checks_schema_before_restoring_images(self):
        rollback = SCRIPT.split('elif [[ "$mode" == rollback ]]', 1)[1].split('elif [[ "$mode" == finish ]]', 1)[0]
        self.assertLess(rollback.index('cmp --silent "$tx/schema.before"'), rollback.index('target=previous; rollout'))
        self.assertIn("exit 1", rollback)
        self.assertNotIn("pg_restore", rollback)

    def test_interpreter_images_join_the_release_contract(self):
        publish = CI_WORKFLOW.split("name: Publish verified release", 1)[1].split("publish-landing:", 1)[0]
        self.assertIn("name: candidate-interpreter", CI_WORKFLOW)
        self.assertIn("docker load --input candidate/interpreter.tar", publish)
        self.assertIn("for component in api worker web interpreter interpreter-executor keycloak; do", publish)
        # Compose rejects a hyphen in an environment key.
        self.assertIn("key=${component//-/_}", publish)
        self.assertIn("images=(api worker web interpreter interpreter-executor keycloak)", SCRIPT)
        self.assertIn('[[ $(wc -l < "$tx/images.env") == 7 ]]', SCRIPT)
        deploy = SCRIPT.split('if [[ "$mode" == deploy ]]', 1)[1].split('elif [[ "$mode" == rollback ]]', 1)[0]
        # The executor is not a Compose service: pull it with the job-scoped credentials before reserving.
        self.assertLess(deploy.index("docker login ghcr.io"), deploy.index("docker pull --quiet"))
        self.assertLess(deploy.index("docker pull --quiet"), deploy.index('> "$state/pending"'))
        # A runtime accepted before the interpreter joined the release has no interpreter container.
        self.assertIn('has_interpreter "$state/current.env"', deploy)
        self.assertIn('--argjson count "${#previous_components[@]}"', deploy)

    def test_keycloak_joins_the_release_only_where_the_host_leaves_it_to_the_release(self):
        backend = CI_WORKFLOW.split("  backend-images:\n", 1)[1].split("\n  secrets:\n", 1)[0]
        self.assertIn("context: infrastructure/keycloak", backend)
        self.assertIn("infrastructure/keycloak/smoke-test-image.sh", backend)
        self.assertIn('"memoryos-keycloak:sha-$GITHUB_SHA"', backend)
        deploy = SCRIPT.split('if [[ "$mode" == deploy ]]', 1)[1].split('elif [[ "$mode" == rollback ]]', 1)[0]
        # Staging names the OrgMemory image in its environment file; the release must not override it.
        strip = deploy.index('sed -i "/^$(image_key keycloak)=/d" "$tx/candidate.env"')
        self.assertLess(deploy.index('cp "$tx/images.env" "$tx/candidate.env"'), strip)
        self.assertIn('grep -q "^$(image_key keycloak)=" "$environment_file"', deploy)
        # A Keycloak started by hand carries another revision; it joins the capture only once a release put it there.
        self.assertIn('has_keycloak "$state/current.env"', deploy)
        # Its database is dumped before a Keycloak that may migrate it starts.
        self.assertLess(deploy.index('-d keycloak -Fc'), deploy.index("target=candidate; rollout"))
        rollout = SCRIPT.split("rollout() {", 1)[1].split("\n}\n", 1)[0]
        self.assertLess(rollout.index("keycloak"), rollout.index(" api"))
        rollback = SCRIPT.split('elif [[ "$mode" == rollback ]]', 1)[1].split('elif [[ "$mode" == finish ]]', 1)[0]
        self.assertNotIn("stop --timeout 45 keycloak", rollback)

    def test_the_release_source_is_readable_and_nothing_else_is(self):
        deploy = SCRIPT.split('if [[ "$mode" == deploy ]]', 1)[1].split('elif [[ "$mode" == rollback ]]', 1)[0]
        # Services started from the release's Compose files read its scripts as their own users;
        # a root-only source is how Keycloak lost its theme and the host kept a hand-made copy.
        extract = deploy.index('tar --extract --file "$tx/configuration.tar"')
        self.assertLess(extract, deploy.index('chmod -R u=rwX,go=rX "$tx/source"'))
        self.assertIn('chmod o+x "$state" "$tx"', deploy)
        # Environment copies, dumps and state files are still created under this mask.
        self.assertIn("\numask 077\n", SCRIPT)
        self.assertNotRegex(SCRIPT, r"chmod[^\n]*(\.env|\.dump|pending|current)")

    def test_only_production_rolls_out_the_serving_node(self):
        # The serving node (MEM-192) holds production's GPU services. Staging has none, the release
        # builds nothing for it, and the application host's script never reaches it.
        publish = CI_WORKFLOW.split("name: Publish verified release", 1)[1].split("publish-landing:", 1)[0]
        for text in (WORKFLOW, publish, SCRIPT):
            self.assertNotIn("inference", text)
        for text in (publish, SCRIPT):
            self.assertNotIn("serving", text)
        start = WORKFLOW.index("- name: Roll out the serving node")
        step = WORKFLOW[start:WORKFLOW.index("- name:", start + 1)]
        self.assertIn("inputs.environment == 'production'", step)
        self.assertIn("ProxyJump deploy-target", step)
        self.assertIn("StrictHostKeyChecking yes", step)
        self.assertLess(start, WORKFLOW.index("id: rollout"), "the worker starts against a serving node already rolled out")
        # The step's own leading comment belongs to it.
        outside = WORKFLOW[:WORKFLOW.rfind("\n\n", 0, start)] + WORKFLOW[WORKFLOW.index("- name:", start + 1):]
        self.assertEqual(["serving-key"], sorted(set(re.findall(r"serving[\w-]*", outside))))
        self.assertIn("sha256sum configuration.tar images.env > SHA256SUMS", publish)
        self.assertIn("{manifest.json,configuration.tar,images.env,SHA256SUMS}", SCRIPT)

    def test_the_serving_firewall_guards_docker_without_being_restarted_by_a_deployment(self):
        deployment = ROOT / "infrastructure/deployment"
        unit = (deployment / "systemd/memoryos-serving-firewall.service").read_text(encoding="utf-8")
        directives = [line.strip() for line in unit.splitlines() if line.strip() and not line.startswith("#")]
        # At boot the rule exists before any container publishes a port, and a failed rule keeps Docker down.
        self.assertIn("Before=docker.service", directives)
        self.assertIn("RequiredBy=docker.service", directives)
        for directive in directives:
            self.assertNotRegex(directive, r"^(After|Requires|PartOf|WantedBy)=.*docker")
        serving = (deployment / "deploy-serving.sh").read_text(encoding="utf-8")
        # Docker requires the unit, so restarting it would restart every container.
        self.assertNotRegex(serving, r"systemctl\s+(re)?start\s+memoryos-serving-firewall")
        applied = serving.index('/usr/local/sbin/memoryos-serving-firewall "$allowed" "${ports[@]}"')
        checked = serving.index("A published port is missing from MEMORYOS_SERVING_PORTS")
        self.assertLess(checked, applied, "a port the firewall would not filter stops the deployment first")
        self.assertLess(checked, serving.index("compose up"))
        self.assertLess(applied, serving.index("compose up"), "the rule is current before containers publish")

    def test_interpreter_is_reachable_only_on_the_internal_network(self):
        compose = (ROOT / "infrastructure/deployment/compose.base.yaml").read_text(encoding="utf-8")
        service = compose.split("\n  interpreter:\n", 1)[1].split("\nnetworks:\n", 1)[0]
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
        compose = (ROOT / "infrastructure/deployment/compose.base.yaml").read_text(encoding="utf-8")
        interpreter = compose.split("\n  interpreter:\n", 1)[1].split("\nnetworks:\n", 1)[0]
        api = compose.split("\n  api:\n", 1)[1].split("\n  worker:\n", 1)[0]
        self.assertIn("API_KEY_FILE: /run/secrets/interpreter_api_key", interpreter)
        self.assertIn("- interpreter_api_key", interpreter)
        # A 0600 operator-owned key is unreadable to capability-dropped root without DAC_OVERRIDE; the first
        # staging rollout of the key failed with PermissionError until the file was widened by hand.
        self.assertRegex(interpreter, r"cap_add:\n\s+- DAC_OVERRIDE")
        self.assertIn("MEMORYOS_INTERPRETER_API_KEY_FILE: /run/secrets/interpreter_api_key", api)
        self.assertIn("- interpreter_api_key", api)
        # The launcher reads any MEMORYOS_<NAME>_FILE rather than naming this key; that behaviour is
        # exercised in test_launcher_secret_files.py.
        launcher = (ROOT / "api/src/main/docker/application-launcher.sh").read_text(encoding="utf-8")
        self.assertIn('export "${secret_variable%_FILE}=$secret_value"', launcher)


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
        self.script = self.root / "deploy.sh"
        self.script.write_text(source)
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
elif args[:2] == ["exec", "memoryos-postgres"] and any("to_regclass" in arg for arg in args):
    # Whether Flyway has run here: a database with no recorded migration has no history table.
    print("t" if (root / "schema").read_text().strip() else "f")
elif args[:2] == ["exec", "memoryos-postgres"]:
    print((root / "schema").read_text(), end="")
elif args[0] == "compose":
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

    def operate(self, mode, environment="staging"):
        return subprocess.run(["bash", str(self.script), mode, self.release, environment],
                              env=self.environment, capture_output=True, text=True, timeout=10)

    def test_an_unknown_environment_is_refused_before_any_runtime_call(self):
        result = self.operate("finish", environment="prod")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.docker_calls(), [])
        self.assertTrue(self.pending.exists())

    def docker_calls(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_healthy_finish_commits_without_business_accounts(self):
        result = self.operate("finish")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.pending.exists())
        self.assertEqual((self.state / "current.env").read_bytes(), (self.tx / "candidate.env").read_bytes())
        self.assertEqual((self.tx / "database.dump").read_bytes(), self.backup)

    def test_failed_application_verification_retains_reservation(self):
        for failure in ("health", "revision"):
            with self.subTest(failure=failure):
                self.set_runtime(unhealthy="worker" if failure == "health" else None,
                                 wrong_revision="web" if failure == "revision" else None)
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



if __name__ == "__main__":
    unittest.main()
