"""A release leaves every one of its components naming the release it came from.

Staging refused to deploy the merge of #356 with "Unhealthy or mixed runtime". Nothing was wrong
with the runtime: a manual dispatch had redeployed the commit already running, Compose reused the
web and interpreter containers because neither their service definition nor their image had
changed, and only the api it had stopped came back under the new release directory. The
com.docker.compose.project.config_files label then held two values, and the next deployment's
capture reads that split as a runtime it must not roll back to.

The capture is right to refuse; the rollout was wrong to produce it.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "infrastructure/deployment/deploy.sh").read_text(encoding="utf-8")


def rollout():
    body = SCRIPT.split("rollout() {", 1)[1]
    return body.split("\n}", 1)[0]


class RolloutTest(unittest.TestCase):
    def test_every_component_a_release_starts_is_recreated(self):
        # Reuse is what splits the label, so no service may be brought up without --force-recreate.
        started = re.findall(r"compose up [^\n]*", rollout())
        self.assertTrue(started, "the rollout starts nothing")
        for command in started:
            self.assertIn("--force-recreate", command, command)

    def test_the_rollout_still_starts_the_whole_release(self):
        # A component left out would keep its old label for the same reason.
        body = rollout()
        for component in ("keycloak", "api", "worker web", "interpreter"):
            self.assertRegex(body, r"compose up [^\n]*%s" % re.escape(component), component)

    def test_the_capture_still_refuses_a_split_runtime(self):
        # The guard this protects: without it a release could roll back to a directory that the
        # containers it would restore never ran from.
        self.assertIn(
            '([.[].Config.Labels["com.docker.compose.project.config_files"]] | unique | length) == 1',
            SCRIPT)
        self.assertIn('error("Unhealthy or mixed runtime")', SCRIPT)


if __name__ == "__main__":
    unittest.main()
