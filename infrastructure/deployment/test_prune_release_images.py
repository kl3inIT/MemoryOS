"""Superseded release images are removed; the running release, its predecessor and the operator's
images are not.

`docker image prune -a` on staging removed the interpreter executor, which no container runs
between Python executions. The interpreter answered 503 and the next deployment refused to replace
a runtime it could not call healthy. This script keeps what the accepted releases name instead.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infrastructure/deployment/prune-release-images.sh"
LINUX_TOOLS = all(shutil.which(tool) for tool in ("bash", "flock", "find"))


def sha(character):
    return "sha256:" + character * 64


@unittest.skipUnless(LINUX_TOOLS and sys.platform != "win32", "executes the script with GNU tools")
class PruneReleaseImagesTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.state = self.root / "deployments"
        self.state.mkdir()
        # Three releases' worth of images, plus an operator image that is not a release's.
        self.images = []
        for release in ("old", "previous", "current"):
            letter = {"old": "1", "previous": "2", "current": "3"}[release]
            for index, component in enumerate(("api", "worker", "web", "interpreter", "interpreter-executor")):
                self.images.append({
                    "id": sha(chr(ord("a") + index) if release == "current" else letter + str(index))[:71].ljust(71, "0"),
                    "repository": "ghcr.io/kl3init/memoryos-" + component,
                    "reference": "ghcr.io/kl3init/memoryos-%s@%s" % (component, sha(letter + str(index))[:71].ljust(71, "0")),
                    "release": release,
                    "component": component,
                })
        self.images.append({"id": sha("f"), "repository": "postgres", "reference": "postgres:18", "release": None})
        self.write_images()

        # The running release names its images by registry reference; the transaction before it
        # recorded a rollback-captured runtime by image ID, which is the other form in use.
        self.write_env(self.state / "current.env", "current", by_reference=True)
        previous = self.state / "previous-release"
        previous.mkdir()
        self.write_env(previous / "candidate.env", "previous", by_reference=False)
        (previous / "result").write_text("previous-release candidate\n")
        os.utime(previous / "result", (1_000, 1_000))
        current = self.state / "current-release"
        current.mkdir()
        self.write_env(current / "candidate.env", "current", by_reference=True)
        (current / "result").write_text("current-release candidate\n")
        os.utime(current / "result", (2_000, 2_000))

        binaries = self.root / "bin"
        binaries.mkdir()
        docker = binaries / "docker"
        docker.write_text(f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
root = Path(os.environ["PRUNE_TEST_ROOT"])
images = json.loads((root / "images.json").read_text())
args = sys.argv[1:]
with (root / "calls.jsonl").open("a") as calls:
    calls.write(json.dumps(args) + "\\n")
if args[:2] == ["image", "inspect"]:
    reference = args[-1]
    match = [image for image in images if image["reference"] == reference]
    if not match:
        sys.exit(1)
    print(match[0]["id"])
elif args[0] == "images":
    for image in images:
        print(image["id"], image["repository"])
elif args[:2] == ["image", "rm"]:
    pass
else:
    sys.exit("unexpected docker call " + repr(args))
''')
        docker.chmod(0o755)
        self.environment = {**os.environ, "PATH": str(binaries) + os.pathsep + os.environ["PATH"],
                            "PRUNE_TEST_ROOT": str(self.root), "MEMORYOS_ROOT": str(self.root)}

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write_images(self):
        (self.root / "images.json").write_text(json.dumps(self.images))

    def write_env(self, path, release, by_reference):
        lines = []
        for image in self.images:
            if image["release"] != release:
                continue
            key = "MEMORYOS_%s_IMAGE" % image["component"].upper().replace("-", "_")
            lines.append("%s=%s" % (key, image["reference"] if by_reference else image["id"]))
        lines.append("MEMORYOS_RELEASE=%s" % ("c" * 40))
        path.write_text("\n".join(lines) + "\n")

    def run_script(self, *arguments):
        return subprocess.run(["bash", str(SCRIPT), *arguments], env=self.environment,
                              capture_output=True, text=True, check=False)

    def removed(self):
        calls = self.root / "calls.jsonl"
        if not calls.exists():
            return set()
        return {json.loads(line)[-1] for line in calls.read_text().splitlines()
                if json.loads(line)[:2] == ["image", "rm"]}

    def ids(self, release):
        return {image["id"] for image in self.images if image["release"] == release}

    def test_keeps_the_running_and_previous_releases_and_removes_the_rest(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.removed(), self.ids("old"))

    def test_the_executor_is_kept_though_no_container_runs_it(self):
        self.run_script()
        executors = {image["id"] for image in self.images
                     if image.get("component") == "interpreter-executor"
                     and image["release"] in ("current", "previous")}
        self.assertEqual(len(executors), 2)
        self.assertFalse(executors & self.removed())

    def test_operator_images_are_never_considered(self):
        self.run_script()
        self.assertNotIn(sha("f"), self.removed())

    def test_nothing_is_removed_while_a_deployment_is_reserved(self):
        (self.state / "pending").write_text("anything\n")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.removed(), set())

    def test_nothing_is_removed_while_a_deployment_holds_the_lock(self):
        import time
        holder = subprocess.Popen(["flock", str(self.state / "lock"), "sleep", "5"])
        try:
            time.sleep(0.5)
            result = self.run_script()
        finally:
            holder.kill()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("owns the lock", result.stdout)
        self.assertEqual(self.removed(), set())

    def test_a_dry_run_removes_nothing_and_says_what_it_would(self):
        result = self.run_script("--dry-run")
        self.assertEqual(self.removed(), set())
        self.assertEqual(result.stdout.count("would remove"), len(self.ids("old")))

    def test_refuses_when_the_accepted_releases_name_no_local_image(self):
        # A keep set that resolved to nothing would mean every release image gets deleted.
        for image in self.images:
            image["reference"] = "gone/" + image["reference"]
        self.write_images()
        for path in self.state.rglob("*.env"):
            path.write_text("MEMORYOS_API_IMAGE=ghcr.io/kl3init/memoryos-api@sha256:" + "9" * 64 + "\n")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.removed(), set())


class InstallationTest(unittest.TestCase):
    def test_the_timer_runs_the_script_from_a_path_a_deployment_does_not_replace(self):
        service = (ROOT / "infrastructure/deployment/systemd/memoryos-prune-release-images.service").read_text()
        self.assertIn("ExecStart=/usr/local/sbin/memoryos-prune-release-images", service)
        self.assertNotIn("/apps/memoryos/incoming", service)
        timer = (ROOT / "infrastructure/deployment/systemd/memoryos-prune-release-images.timer").read_text()
        self.assertIn("Persistent=true", timer)

    def test_only_release_images_are_candidates(self):
        script = SCRIPT.read_text()
        self.assertIn("ghcr.io/kl3init/memoryos-*", script)
        self.assertNotIn("prune -a", script.replace("# ", "").split("set -Eeuo")[1])
        self.assertNotIn("image rm -f", script)


if __name__ == "__main__":
    unittest.main()
