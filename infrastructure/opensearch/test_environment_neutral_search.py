"""What both environments share must name neither of them.

The Search configuration, the security bootstrap and the provisioning script are mounted by
staging and by production alike. Every name that differs between the two belongs to the
environment that starts the node, never to the file they share: production once would have run a
cluster called `memoryos-search-staging`, and `securityadmin.sh` refuses a name the node does not
carry, so the two wrong values only worked because they were wrong together.
"""

import importlib.util
from pathlib import Path
import re
import unittest


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEPLOYMENT = ROOT / "infrastructure/deployment"

# Mounted by both environments, so neither environment's name may appear in them.
SHARED = (
    HERE / "opensearch.yml",
    HERE / "bootstrap-security.sh",
    HERE / "provision-search.py",
    HERE / "security/config.yml",
    HERE / "security/roles.yml",
    HERE / "security/roles_mapping.yml",
)


def load(name):
    specification = importlib.util.spec_from_file_location(name, HERE / (name + ".py"))
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


provision = load("provision-search")


class SharedFilesNameNoEnvironmentTest(unittest.TestCase):
    def test_no_shared_file_hard_codes_an_environment(self):
        for path in SHARED:
            text = path.read_text(encoding="utf-8")
            executable = "\n".join(line for line in text.splitlines()
                                   if not line.lstrip().startswith(("#", "//")))
            for environment in ("staging", "production"):
                self.assertNotIn(environment, executable,
                                 "%s names %s" % (path.name, environment))

    def test_each_environment_names_its_own_cluster_and_tells_the_bootstrap_the_same(self):
        for environment in ("staging", "production"):
            overlay = (DEPLOYMENT / ("compose.search.%s.yaml" % environment)).read_text(encoding="utf-8")
            expected = "memoryos-search-" + environment
            self.assertIn("cluster.name: %s" % expected, overlay, environment)
            self.assertIn("MEMORYOS_SEARCH_CLUSTER_NAME: %s" % expected, overlay, environment)

    def test_the_bootstrap_refuses_to_run_without_being_told_the_cluster(self):
        script = (HERE / "bootstrap-security.sh").read_text(encoding="utf-8")
        self.assertIn('${MEMORYOS_SEARCH_CLUSTER_NAME:?', script)
        self.assertIn('-cn "$MEMORYOS_SEARCH_CLUSTER_NAME"', script)


class DashboardsIsOptionalTest(unittest.TestCase):
    def test_dropping_the_marked_blocks_leaves_the_service_able_to_authenticate(self):
        configuration = provision.without_dashboards(
            (HERE / "security/config.yml").read_text(encoding="utf-8"))
        # What the api and worker use to reach the cluster must survive.
        self.assertIn("basic_internal_auth_domain", configuration)
        self.assertIn("anonymous_auth_enabled: false", configuration)
        # What only a browser reaching Dashboards needs must not.
        for absent in ("openid_auth_domain", "__OIDC_ISSUER__", "memoryos-dashboards", "kibana:"):
            self.assertNotIn(absent, configuration, absent)
        # A marker left behind would mean a block was half removed.
        self.assertNotIn("dashboards", configuration.lower())

    def test_dropping_leaves_the_roles_the_service_is_mapped_to(self):
        mapping = provision.without_dashboards(
            (HERE / "security/roles_mapping.yml").read_text(encoding="utf-8"))
        self.assertIn("memoryos_search", mapping)
        self.assertIn("memoryos-service", mapping)
        self.assertNotIn("kibana_server", mapping)
        self.assertNotIn("memoryos-dashboards", mapping)

    def test_every_marked_block_is_closed(self):
        for path in (HERE / "security/config.yml", HERE / "security/roles_mapping.yml"):
            text = path.read_text(encoding="utf-8")
            opened = len(re.findall(r"^\s*# >>> dashboards", text, re.M))
            closed = len(re.findall(r"^\s*# <<< dashboards\s*$", text, re.M))
            self.assertEqual(opened, closed, "%s: %d opened, %d closed" % (path.name, opened, closed))
            self.assertGreater(opened, 0, path.name)

    def test_the_certificate_set_follows_the_deployment(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(sorted(provision.leaf_certificates()), ["admin", "node"])
        with patch.dict(os.environ, {"MEMORYOS_OPENSEARCH_DASHBOARDS_PUBLIC_URL": "https://search.example"},
                        clear=True):
            self.assertEqual(sorted(provision.leaf_certificates()), ["admin", "dashboards", "node"])

    def test_an_issuer_without_dashboards_is_refused(self):
        # An issuer configured where no browser can reach Dashboards is a setting that does
        # nothing, and a setting that does nothing is read as one that does something.
        script = (HERE / "provision-search.py").read_text(encoding="utf-8")
        self.assertIn("an issuer was given but no Dashboards is published", script)


if __name__ == "__main__":
    unittest.main()
