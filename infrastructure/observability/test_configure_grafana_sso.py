"""Exercise credential redirect protection against real loopback HTTP servers."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.error

spec = importlib.util.spec_from_file_location(
    "grafana_sso", Path(__file__).with_name("configure-grafana-sso.py"))
sso = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sso)


class RedirectProtectionTest(unittest.TestCase):
    def test_token_and_admin_redirects_never_reach_another_origin(self):
        for stage in ("token", "admin"):
            for status in (301, 302, 303, 307, 308):
                with self.subTest(stage=stage, status=status):
                    self.check_redirect(stage, status)

    def check_redirect(self, stage, status):
        received = []

        class Destination(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append(self.path)
                self.send_response(200)
                self.end_headers()

            do_POST = do_GET

            def log_message(self, *args):
                pass

        destination = ThreadingHTTPServer(("127.0.0.1", 0), Destination)

        class Keycloak(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                if stage == "token":
                    self.redirect()
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(json.dumps({"access_token": "test-only-token"}).encode())

            def do_GET(self):
                self.redirect()

            def redirect(self):
                self.send_response(status)
                self.send_header("Location", f"http://127.0.0.1:{destination.server_port}/capture")
                self.end_headers()

            def log_message(self, *args):
                pass

        source = ThreadingHTTPServer(("127.0.0.1", 0), Keycloak)
        threads = [threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
                   for server in (source, destination)]
        for thread in threads:
            thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                secret = Path(directory) / "secret"
                secret.write_text("test-only-secret-" * 3)
                with patch.dict(os.environ, {
                    "KEYCLOAK_URL": f"http://127.0.0.1:{source.server_port}",
                    "MEMORYOS_GRAFANA_PUBLIC_URL": "https://grafana.example.test",
                    "MEMORYOS_GRAFANA_OIDC_SECRET_FILE": str(secret),
                    "KEYCLOAK_ADMIN_USERNAME": "test-admin",
                    "KC_CLI_PASSWORD": "test-only-password",
                }):
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        sso.reconcile()
                    self.assertEqual(status, error.exception.code)
                    self.assertEqual([], received)
        finally:
            for server in (source, destination):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join()


if __name__ == "__main__":
    unittest.main()
