"""A server reads its secrets from files it holds, not from a service it has to reach at start."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
DEPLOYMENT = ROOT / "infrastructure/deployment"
BASE = (DEPLOYMENT / "compose.base.yaml").read_text(encoding="utf-8")
ENTRYPOINT = (ROOT / "api/src/main/docker/api-entrypoint.sh").read_text(encoding="utf-8")
LAUNCHER = (ROOT / "api/src/main/docker/application-launcher.sh").read_text(encoding="utf-8")
DOCKERFILE = (ROOT / "Dockerfile").read_text(encoding="utf-8")

# Every secret the running services read, and the Compose secret each one arrives through.
MOUNTED = {
    "MEMORYOS_DATABASE_PASSWORD_FILE": "database_password",
    "MEMORYOS_BROWSER_CLIENT_SECRET_FILE": "browser_client_secret",
    "MEMORYOS_KEYCLOAK_ADMIN_CLIENT_SECRET_FILE": "keycloak_admin_client_secret",
    "MEMORYOS_OPENSEARCH_PASSWORD_FILE": "opensearch_password",
    "MEMORYOS_EXTRACTION_DOCLING_API_KEY_FILE": "docling_api_key",
    "MEMORYOS_CHAT_CATALOG_ENCRYPTION_KEY_FILE": "chat_catalog_encryption_key",
    "MEMORYOS_GOOGLE_DRIVE_CREDENTIAL_ENCRYPTION_KEY_FILE": "google_drive_credential_encryption_key",
    "MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY_FILE": "mcp_credential_encryption_key",
    "MEMORYOS_CHAT_API_KEY_FILE": "model_api_key",
    "MEMORYOS_EMBEDDING_API_KEY_FILE": "model_api_key",
}


class ServerSecretSourceTest(unittest.TestCase):
    def test_nothing_on_a_server_calls_the_vault(self):
        # A vault reached at container start is a service outside the deployment that can keep it
        # from starting; the bootstrap credential sitting beside it also unlocked the whole
        # environment, so it never was the boundary it looked like.
        #
        # Comments are read past: they explain why the vault is gone and where it still belongs.
        for name, text in (("entry point", ENTRYPOINT), ("launcher", LAUNCHER),
                           ("base composition", BASE), ("image", DOCKERFILE)):
            executable = "\n".join(line for line in text.splitlines()
                                   if not line.lstrip().startswith("#"))
            self.assertNotRegex(executable, r"(?i)infisical", name)

    def test_the_entry_point_only_hands_over_to_the_launcher(self):
        self.assertIn("exec /usr/local/bin/memoryos-launcher", ENTRYPOINT)
        self.assertNotIn("login", ENTRYPOINT)

    def test_every_secret_arrives_as_a_mounted_file(self):
        for variable, secret in MOUNTED.items():
            self.assertIn("%s: /run/secrets/%s" % (variable, secret), BASE, variable)
            self.assertRegex(BASE, r"\n  %s:\n    file: " % re.escape(secret), secret)

    def test_the_application_never_receives_a_secret_as_a_plain_value(self):
        # The value itself must not appear in the environment of the api or worker container:
        # docker inspect, a crash log and /proc/<pid>/environ would all carry it.
        #
        # postgres and keycloak still take their database passwords this way, because the database
        # container creates its own roles at first start and its bootstrap script reads the value.
        # Moving those is its own change; see docs/increments/active/server-secrets-as-files.
        for service in ("api", "worker"):
            block = BASE.split("\n  %s:\n" % service, 1)[1].split("\n  web:\n", 1)[0]
            for variable in MOUNTED:
                plain = variable[: -len("_FILE")]
                self.assertNotRegex(block, r"\n\s+%s: \$\{" % re.escape(plain), "%s/%s" % (service, plain))

    def test_the_embedding_key_keeps_working_for_developer_machines(self):
        search = (ROOT / "core/src/main/resources/memoryos-search.yaml").read_text(encoding="utf-8")
        self.assertIn("${MEMORYOS_EMBEDDING_API_KEY:${SPRING_AI_OPENAI_API_KEY:}}", search)


if __name__ == "__main__":
    unittest.main()
