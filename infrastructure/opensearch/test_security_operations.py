"""Operator boundaries using generated test certificates; no live credentials."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


keycloak = load("configure-keycloak-client")
provision = load("provision-search")
dashboards = load("provision-dashboards")


class KeycloakBoundaryTest(unittest.TestCase):
    def test_rejects_untrusted_urls_before_credentials_or_network_access(self):
        for origin in ["http://auth.kl3in.tech", "https://auth.kl3in.tech.evil.example",
                       "https://user@auth.kl3in.tech", "https://auth.kl3in.tech:8443",
                       "https://auth.kl3in.tech/other", "https://auth.kl3in.tech?redirect=1"]:
            with self.subTest(origin=origin), patch.dict(os.environ, {"KEYCLOAK_URL": origin}, clear=True), \
                    patch.object(keycloak.urllib.request, "build_opener") as network:
                with self.assertRaises(ValueError):
                    keycloak.main()
                network.assert_not_called()
        self.assertEqual("https://auth.kl3in.tech", keycloak.keycloak_origin("https://auth.kl3in.tech/"))

    def test_never_forwards_administration_credentials_through_redirects(self):
        with self.assertRaises(ValueError):
            keycloak.NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://elsewhere.example")


class DashboardsSavedObjectsTest(unittest.TestCase):
    @staticmethod
    def response(status, body):
        completed = Mock()
        completed.returncode = 0
        completed.stdout = json.dumps(body) + "\n" + str(status)
        completed.stderr = ""
        return completed

    @staticmethod
    def saved_object(object_type, object_id, desired):
        return {"type": object_type, "id": object_id, **json.loads(json.dumps(desired))}

    def test_creates_and_verifies_global_tenant_objects_without_exposing_password(self):
        responses = [
            self.response(404, {"statusCode": 404}),
            self.response(200, self.saved_object("index-pattern", dashboards.INDEX_PATTERN_ID, dashboards.INDEX_PATTERN)),
            self.response(200, self.saved_object("index-pattern", dashboards.INDEX_PATTERN_ID, dashboards.INDEX_PATTERN)),
            self.response(404, {"statusCode": 404}),
            self.response(200, self.saved_object("search", dashboards.SAVED_SEARCH_ID, dashboards.SAVED_SEARCH)),
            self.response(200, self.saved_object("search", dashboards.SAVED_SEARCH_ID, dashboards.SAVED_SEARCH)),
        ]
        with patch.object(dashboards.subprocess, "run", side_effect=responses) as run:
            self.assertEqual("reconciled", dashboards.reconcile("index-pattern", dashboards.INDEX_PATTERN_ID,
                                                                 dashboards.INDEX_PATTERN))
            self.assertEqual("reconciled", dashboards.reconcile("search", dashboards.SAVED_SEARCH_ID,
                                                                 dashboards.SAVED_SEARCH))
        calls = run.call_args_list
        self.assertEqual(["GET", "POST", "GET", "GET", "POST", "GET"],
                         [call.args[0][call.args[0].index("--request") + 1] for call in calls])
        for call in calls:
            command = call.args[0]
            self.assertIn("securitytenant: global_tenant", command)
            self.assertIn("/run/secrets/dashboards-bootstrap.curl", command)
            self.assertNotIn("memoryos-dashboards:", " ".join(command))
        self.assertEqual(dashboards.SAVED_SEARCH, json.loads(calls[4].kwargs["input"]))

    def test_index_pattern_fields_come_from_field_capabilities_without_exposing_the_certificate(self):
        capabilities = {"fields": {
            "updated_at": {"date": {"searchable": True, "aggregatable": True}},
            "content": {"text": {"searchable": True, "aggregatable": False}},
            "source_metadata.source_id": {"keyword": {"searchable": True, "aggregatable": True}},
        }}
        with tempfile.TemporaryDirectory(prefix="memoryos-admin-cert-") as name:
            for filename in ("admin.crt", "admin.key", "ca.crt"):
                Path(name, filename).write_text("PEM " + filename + "\n", encoding="utf-8")
            completed = Mock(returncode=0, stdout=json.dumps(capabilities), stderr="")
            with patch.object(dashboards, "SECRET_DIRECTORY", Path(name)), \
                    patch.object(dashboards.subprocess, "run", return_value=completed) as run:
                fields = dashboards.index_fields()
        command = run.call_args.args[0]
        self.assertEqual(["docker", "exec", "-i", dashboards.OPENSEARCH_CONTAINER, "sh", "-c"], command[:6])
        self.assertNotIn("PEM", " ".join(command))
        self.assertIn("PEM admin.key", run.call_args.kwargs["input"])
        by_name = {field["name"]: field for field in fields}
        self.assertEqual("date", by_name["updated_at"]["type"])
        self.assertTrue(by_name["updated_at"]["readFromDocValues"])
        self.assertEqual("string", by_name["content"]["type"])
        self.assertFalse(by_name["content"]["aggregatable"])
        self.assertEqual({"nested": {"path": "source_metadata"}}, by_name["source_metadata.source_id"]["subType"])
        desired = dashboards.index_pattern_with_fields(fields)
        self.assertEqual("updated_at", desired["attributes"]["timeFieldName"])
        self.assertEqual(fields, json.loads(desired["attributes"]["fields"]))

    def test_index_fields_fail_closed_without_the_time_field(self):
        completed = Mock(returncode=0, stdout=json.dumps({"fields": {"content": {"text": {}}}}), stderr="")
        with tempfile.TemporaryDirectory(prefix="memoryos-admin-cert-") as name:
            for filename in ("admin.crt", "admin.key", "ca.crt"):
                Path(name, filename).write_text("PEM\n", encoding="utf-8")
            with patch.object(dashboards, "SECRET_DIRECTORY", Path(name)), \
                    patch.object(dashboards.subprocess, "run", return_value=completed):
                with self.assertRaisesRegex(RuntimeError, "time field"):
                    dashboards.index_fields()

    def test_replay_is_read_only_when_controlled_state_matches(self):
        current_index = self.saved_object("index-pattern", dashboards.INDEX_PATTERN_ID, dashboards.INDEX_PATTERN)
        current_index["attributes"]["fields"] = "runtime-managed"
        current_search = self.saved_object("search", dashboards.SAVED_SEARCH_ID, dashboards.SAVED_SEARCH)
        with patch.object(dashboards.subprocess, "run", side_effect=[
                self.response(200, current_index), self.response(200, current_search)]) as run:
            self.assertEqual("unchanged", dashboards.reconcile("index-pattern", dashboards.INDEX_PATTERN_ID,
                                                                dashboards.INDEX_PATTERN))
            self.assertEqual("unchanged", dashboards.reconcile("search", dashboards.SAVED_SEARCH_ID,
                                                                dashboards.SAVED_SEARCH))
        self.assertEqual(2, run.call_count)

    def test_updates_drift_and_fails_closed_when_verification_differs(self):
        drifted = self.saved_object("search", dashboards.SAVED_SEARCH_ID, dashboards.SAVED_SEARCH)
        drifted["attributes"] = {**drifted["attributes"], "title": "Changed"}
        with patch.object(dashboards.subprocess, "run", side_effect=[
                self.response(200, drifted),
                self.response(200, drifted),
                self.response(200, drifted)]):
            with self.assertRaisesRegex(RuntimeError, "verification failed"):
                dashboards.reconcile("search", dashboards.SAVED_SEARCH_ID, dashboards.SAVED_SEARCH)

    def test_inspector_role_remains_saved_object_and_document_read_only(self):
        roles = (Path(__file__).parent / "security" / "roles.yml").read_text(encoding="utf-8")
        inspector = roles.split("memoryos_search_inspector:", 1)[1]
        self.assertIn("kibana_all_read", inspector)
        self.assertNotIn("kibana_all_write", inspector)
        self.assertNotIn("all_access", inspector)

    def test_inspector_can_open_discover_because_dashboards_read_only_mode_is_disabled(self):
        # The security plugin falls back to kibana_read_only when no roles are configured, and that mode hides Discover.
        config = (Path(__file__).parent / "opensearch_dashboards.yml").read_text(encoding="utf-8")
        self.assertIn("opensearch_security.readonly_mode.roles: []", config)
        mapping = (Path(__file__).parent / "security" / "roles_mapping.yml").read_text(encoding="utf-8")
        self.assertNotIn("kibana_read_only", mapping)

    def test_bootstrap_credential_is_generated_as_a_private_curl_config(self):
        with tempfile.TemporaryDirectory(prefix="memoryos-dashboards-config-") as name:
            directory = Path(name)
            (directory / "dashboards-password.txt").write_text("test-secret\n", encoding="utf-8")
            provision.write_dashboards_bootstrap_config(directory)
            config = directory / "dashboards-bootstrap.curl"
            self.assertEqual('user = "memoryos-dashboards:test-secret"\n', config.read_text(encoding="utf-8"))
            if os.name == "posix":
                self.assertEqual(0o600, config.stat().st_mode & 0o777)


@unittest.skipUnless(os.name == "posix", "certificate operator runs on Linux")
class CertificateRenewalTest(unittest.TestCase):
    def setUp(self):
        self.previous_umask = os.umask(0o077)
        self.temporary = tempfile.TemporaryDirectory(prefix="memoryos-certificate-test-")
        self.addCleanup(self.temporary.cleanup)
        self.addCleanup(os.umask, self.previous_umask)
        self.directory = Path(self.temporary.name)
        provision.run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(self.directory / "ca.key"),
                      "-out", str(self.directory / "ca.crt"), "-sha256", "-days", "3650", "-subj", "/CN=Renewal Test CA")
        for name, definition in provision.LEAF_CERTIFICATES.items():
            provision.certificate(self.directory, name, *definition)
        (self.directory / "service-password.txt").write_text("test-only-service-password")

    def snapshot(self):
        return {p.name: p.read_bytes() for p in self.directory.iterdir() if p.suffix in (".crt", ".key", ".txt")}

    def test_preserves_fresh_certificates_and_rotates_due_leafs_without_changing_authority_or_credentials(self):
        before = self.snapshot()
        with patch.object(provision, "restart_and_wait") as restart:
            self.assertEqual([], provision.renew_certificates(self.directory, 30))
            restart.assert_not_called()
            self.assertEqual(before, self.snapshot())
            self.assertEqual(["node", "admin", "dashboards"], provision.renew_certificates(self.directory, 366))
            self.assertEqual([("memoryos-opensearch",), ("memoryos-opensearch-dashboards",)],
                             [call.args for call in restart.call_args_list])
        after = self.snapshot()
        for filename in ["ca.key", "ca.crt", "service-password.txt"]:
            self.assertEqual(before[filename], after[filename])
        for name in provision.LEAF_CERTIFICATES:
            self.assertNotEqual(before[name + ".crt"], after[name + ".crt"])
            self.assertNotEqual(before[name + ".key"], after[name + ".key"])
            self.assertEqual(0o600, (self.directory / (name + ".key")).stat().st_mode & 0o777)
        provision.run("openssl", "verify", "-CAfile", str(self.directory / "ca.crt"),
                      "-verify_hostname", "memoryos-opensearch-dashboards", str(self.directory / "dashboards.crt"))

    def test_restores_original_leaf_pairs_when_runtime_reload_fails(self):
        before = self.snapshot()
        with patch.object(provision, "restart_and_wait", side_effect=[RuntimeError("reload failed"), None, None]):
            with self.assertRaisesRegex(RuntimeError, "reload failed"):
                provision.renew_certificates(self.directory, 366)
        self.assertEqual(before, self.snapshot())
        self.assertEqual(1, len(list((self.directory / "certificate-backups").iterdir())))


if __name__ == "__main__":
    unittest.main()
