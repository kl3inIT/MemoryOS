"""Operator boundaries using generated test certificates; no live credentials."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


keycloak = load("configure-keycloak-client")
provision = load("provision-staging")


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
