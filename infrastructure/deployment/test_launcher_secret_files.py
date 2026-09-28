"""The launcher turns every mounted MEMORYOS_<NAME>_FILE into MEMORYOS_<NAME> before the JVM starts."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "api/src/main/docker/application-launcher.sh"


@unittest.skipUnless(os.name == "posix" and shutil.which("sh"), "The launcher is a POSIX shell script")
class LauncherSecretFileTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

        # The launcher ends by handing the process to the memoryos user; this stands in for that,
        # reporting the environment the JVM would have received.
        binaries = self.root / "bin"
        binaries.mkdir()
        su = binaries / "su"
        su.write_text("#!/bin/sh\nexec env\n")
        su.chmod(0o755)
        self.environment = {
            "PATH": str(binaries) + os.pathsep + os.environ.get("PATH", ""),
            "MEMORYOS_APPLICATION_JAR": "api.jar",
        }

    def secret(self, name, value):
        path = self.root / name
        path.write_text(value)
        return str(path)

    def launch(self, **variables):
        result = subprocess.run(["sh", str(LAUNCHER)],
                                env={**self.environment, **variables},
                                capture_output=True, text=True, timeout=20)
        exported = {}
        for line in result.stdout.splitlines():
            key, _, value = line.partition("=")
            exported[key] = value
        return result, exported

    def test_every_mounted_secret_file_becomes_its_variable(self):
        result, exported = self.launch(
            MEMORYOS_REDIS_PASSWORD_FILE=self.secret("redis", "redis-secret"),
            MEMORYOS_OBJECT_STORAGE_SECRET_KEY_FILE=self.secret("minio", "minio-secret"),
            MEMORYOS_INTERPRETER_API_KEY_FILE=self.secret("interpreter", "interpreter-secret"),
            # A secret nobody wrote code for: the point of the change.
            MEMORYOS_DATABASE_PASSWORD_FILE=self.secret("database", "database-secret"),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(exported.get("MEMORYOS_REDIS_PASSWORD"), "redis-secret")
        self.assertEqual(exported.get("MEMORYOS_OBJECT_STORAGE_SECRET_KEY"), "minio-secret")
        self.assertEqual(exported.get("MEMORYOS_INTERPRETER_API_KEY"), "interpreter-secret")
        self.assertEqual(exported.get("MEMORYOS_DATABASE_PASSWORD"), "database-secret")

    def test_the_redis_authority_is_passed_as_a_file_reference(self):
        # Spring reads the certificate from a path, so this one keeps its own handling.
        result, exported = self.launch(
            MEMORYOS_REDIS_TLS_CA_FILE=self.secret("ca.crt", "-----BEGIN CERTIFICATE-----\n"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(exported.get("MEMORYOS_REDIS_TLS_CA_CERTIFICATE"), "file:/tmp/memoryos-redis-ca.crt")
        self.assertNotIn("MEMORYOS_REDIS_TLS_CA", exported)

    def test_a_variable_from_another_tool_is_left_alone(self):
        # Exporting SSL_CERT from SSL_CERT_FILE would be wrong and hard to notice.
        result, exported = self.launch(SSL_CERT_FILE=self.secret("bundle.pem", "authority"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("SSL_CERT", exported)

    def test_a_missing_or_empty_secret_stops_the_process(self):
        for variables in (
            {"MEMORYOS_DATABASE_PASSWORD_FILE": str(self.root / "absent")},
            {"MEMORYOS_DATABASE_PASSWORD_FILE": self.secret("empty", "")},
        ):
            result, _ = self.launch(**variables)
            self.assertNotEqual(result.returncode, 0, variables)

    def test_an_unset_file_variable_is_not_an_error(self):
        result, exported = self.launch(MEMORYOS_DATABASE_PASSWORD_FILE="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("MEMORYOS_DATABASE_PASSWORD", exported)


if __name__ == "__main__":
    sys.exit(unittest.main())
