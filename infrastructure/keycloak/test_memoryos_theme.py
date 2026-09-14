"""Static contract tests for the repository-owned MemoryOS Keycloak theme."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
THEME = ROOT / "infrastructure" / "keycloak" / "themes" / "memoryos" / "login"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class MemoryOsThemeContractTest(unittest.TestCase):
    def test_extends_keycloak_v2_without_overriding_freemarker(self):
        properties = read(THEME / "theme.properties")

        self.assertRegex(properties, r"(?m)^parent=keycloak\.v2$")
        self.assertRegex(properties, r"(?m)^styles=css/styles\.css css/memoryos\.css$")
        self.assertRegex(properties, r"(?m)^darkMode=false$")
        self.assertEqual([], list(THEME.glob("*.ftl")))

    def test_declared_resources_exist_and_are_local(self):
        properties = read(THEME / "theme.properties")
        custom_css = THEME / "resources" / "css" / "memoryos.css"
        css = read(custom_css)

        for relative_path in (
            "resources/css/memoryos.css",
            "resources/img/favicon.svg",
            "resources/img/lock.svg",
            "resources/img/memoryos-mark.svg",
            "resources/img/meaning-network.svg",
            "messages/messages_en.properties",
        ):
            with self.subTest(relative_path=relative_path):
                self.assertTrue((THEME / relative_path).is_file())

        self.assertIn("favicons=img/favicon.svg", properties)
        self.assertNotRegex(css, r"https?://")
        self.assertNotRegex(css, r"@import\s")

    def test_covers_keycloak_forms_and_approved_visual_contract(self):
        css = read(THEME / "resources" / "css" / "memoryos.css")
        messages = read(THEME / "messages" / "messages_en.properties")

        for selector in (
            "#kc-form-login",
            "#kc-passwd-update-form",
            'body[data-page-id*="reset-password"]',
            'body[data-page-id*="verify-email"]',
            'body[data-page-id="login-info"]',
            'body[data-page-id="login-error"]',
        ):
            with self.subTest(selector=selector):
                self.assertIn(selector, css)

        self.assertIn("Continue to your memory.", messages)
        self.assertIn("Create your password.", messages)
        self.assertIn("Check your inbox.", messages)
        self.assertNotIn("PRIVATE BY DESIGN", css)
        self.assertNotIn("auth.kl3in.tech", css)

    def test_compose_mount_and_realm_reconciliation_are_fail_closed(self):
        compose = read(ROOT / "infrastructure" / "deployment" / "compose.base.yaml")
        reconcile = read(ROOT / "infrastructure" / "keycloak" / "configure-memoryos-realm.sh")

        self.assertIn(
            "../keycloak/themes/memoryos:/opt/keycloak/themes/memoryos:ro",
            compose,
        )
        self.assertRegex(reconcile, r'loginTheme:\s*"memoryos"')
        self.assertIn("get serverinfo", reconcile)
        self.assertNotIn("--fields themes", reconcile)
        self.assertIn("MemoryOS login theme is not available to Keycloak", reconcile)
        self.assertIn("MemoryOS realm login theme did not converge", reconcile)


if __name__ == "__main__":
    unittest.main()
