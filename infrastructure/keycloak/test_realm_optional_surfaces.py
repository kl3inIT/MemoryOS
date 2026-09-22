"""The realm script serves an environment that runs no inspection tooling and sends no mail."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "infrastructure/keycloak/configure-memoryos-realm.sh").read_text(encoding="utf-8")

OPTIONAL = (
    ("MAILPIT", "MEMORYOS_MAILPIT_PUBLIC_URL", "MEMORYOS_MAILPIT_OAUTH2_CLIENT_SECRET"),
    ("PGWEB", "MEMORYOS_PGWEB_PUBLIC_URL", "MEMORYOS_PGWEB_OAUTH2_CLIENT_SECRET"),
    ("REDISINSIGHT", "MEMORYOS_REDISINSIGHT_PUBLIC_URL", "MEMORYOS_REDISINSIGHT_OAUTH2_CLIENT_SECRET"),
    ("MINIO_CONSOLE", "MEMORYOS_MINIO_CONSOLE_PUBLIC_URL", "MEMORYOS_MINIO_CONSOLE_OIDC_CLIENT_SECRET"),
)


class RealmOptionalSurfacesTest(unittest.TestCase):
    def test_staging_only_surfaces_are_not_required(self):
        # Production runs no inspection tooling, so demanding their URLs would force either a second
        # script or ownerless OAuth clients in its realm.
        for _, url, secret in OPTIONAL:
            for name in (url, secret):
                self.assertNotIn('%s:?' % name, SCRIPT, name)
        for name in ("MEMORYOS_KEYCLOAK_SMTP_HOST", "MEMORYOS_KEYCLOAK_SMTP_FROM"):
            self.assertNotIn('%s:?' % name, SCRIPT, name)

    def test_half_a_configuration_is_refused(self):
        # A URL without its secret is a mistake; silently skipping it would hide a broken deployment.
        self.assertIn("needs both its public URL and its client secret, or neither", SCRIPT)
        for _, url, secret in OPTIONAL:
            self.assertRegex(SCRIPT, re.compile(r'check_pair [^\n]*"\$\{%s:-\}" "\$\{%s:-\}"' % (url, secret)))

    def test_every_optional_client_is_built_and_upserted_behind_its_own_switch(self):
        for label, _, _ in OPTIONAL:
            switch = 'if [ "$%s_ENABLED" = true ]; then' % label
            self.assertGreaterEqual(SCRIPT.count(switch), 2, label)

    def test_a_realm_without_mail_says_so_instead_of_pretending(self):
        # Keycloak owns invitation and verification mail. With no server, asking for verified e-mail
        # would block every new sign-in behind a message nobody can deliver.
        disabled = SCRIPT.split('if [ "$SMTP_ENABLED" != true ]; then', 1)[1].split("else", 1)[0]
        self.assertIn("verifyEmail: false", disabled)
        self.assertIn("smtpServer: {}", disabled)
        enabled = SCRIPT.split("else", 1)[1]
        self.assertIn("verifyEmail: true", enabled)

    def test_staging_behaviour_is_unchanged(self):
        # Every staging value is still validated when it is supplied.
        self.assertIn("MEMORYOS_MAILPIT_PUBLIC_URL must be an exact HTTPS nip.io origin", SCRIPT)
        self.assertIn("inspection public URLs must be exact HTTPS origins", SCRIPT)
        self.assertIn("MEMORYOS_KEYCLOAK_SMTP_PORT must be numeric", SCRIPT)
        self.assertIn("MEMORYOS_KEYCLOAK_SMTP_USERNAME is required when SMTP auth is enabled", SCRIPT)


class RealmBootstrapTest(unittest.TestCase):
    def test_the_script_builds_a_realm_that_is_not_there_yet(self):
        # It used to assert the realm existed, so the first environment could only be built by
        # hand and nothing recorded how. A new node now gets its realm from the same run that
        # reconciles an old one.
        self.assertIn('"$KCADM" create realms', SCRIPT)
        self.assertIn('action=created', SCRIPT)
        self.assertIn('action=reused', SCRIPT)

    def test_the_summary_reports_the_realm_that_was_built(self):
        # Reporting required verification on a realm that can send no mail sends the next reader
        # looking for a mail server that was deliberately left out.
        self.assertIn('email-verification=disabled smtp=none', SCRIPT)
        self.assertIn('email-verification=required smtp=configured', SCRIPT)


if __name__ == "__main__":
    unittest.main()
