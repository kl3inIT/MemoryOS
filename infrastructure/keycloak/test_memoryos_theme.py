"""Static contract tests for the repository-owned MemoryOS Keycloak theme.

The rules here are the ones every version of the theme has to keep: it restyles keycloak.v2
rather than replacing its markup, it serves every byte from this repository, and the deployment
mounts it and the realm reconciliation refuses to converge without it. What the page looks like —
which selectors it styles, what the headings say — belongs to whoever owns the design, and is not
frozen here; the tests that did freeze one version failed the moment a different one was restored.
"""

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
        # Keycloak's own templates keep owning every authentication and required-action form.
        self.assertEqual([], list(THEME.glob("*.ftl")))

    def test_every_byte_the_theme_serves_comes_from_this_repository(self):
        css = read(THEME / "resources" / "css" / "memoryos.css")
        properties = read(THEME / "theme.properties")

        self.assertTrue((THEME / "resources" / "css" / "memoryos.css").is_file())
        self.assertTrue((THEME / "messages" / "messages_en.properties").is_file())
        # A login page that fetches a font or a background from elsewhere tells that host who is
        # signing in, and stops working when that host does.
        self.assertNotRegex(css, r"https?://")
        self.assertNotRegex(css, r"@import\s")

        # keycloak.v2 ships this one, and a theme that extends it keeps naming it.
        inherited = {"css/styles.css"}
        declared = re.findall(r"(?m)^(?:styles|favicons)=(.+)$", properties)
        for entry in " ".join(declared).split():
            if entry in inherited:
                continue
            with self.subTest(declared=entry):
                self.assertTrue((THEME / "resources" / entry).is_file(),
                                "theme.properties declares %s, which is not in the theme" % entry)

    def test_the_images_the_theme_keeps_are_the_images_it_uses(self):
        # The previous design left four images behind when it was replaced. An image nobody
        # references is one more thing a reader has to decide about.
        css = read(THEME / "resources" / "css" / "memoryos.css")
        properties = read(THEME / "theme.properties")
        used = {Path(reference).name for reference in re.findall(r'url\("?([^")]+)"?\)', css)}
        used |= {Path(entry).name for entry in " ".join(
            re.findall(r"(?m)^favicons=(.+)$", properties)).split()}

        for image in (THEME / "resources" / "img").glob("*"):
            with self.subTest(image=image.name):
                self.assertIn(image.name, used, "%s is in the theme but nothing references it" % image.name)
        for name in used:
            with self.subTest(used=name):
                self.assertTrue((THEME / "resources" / "img" / name).is_file(),
                                "the theme references %s, which is not in it" % name)

    def test_the_theme_ships_in_the_image_and_realm_reconciliation_is_fail_closed(self):
        dockerfile = read(ROOT / "infrastructure" / "keycloak" / "Dockerfile")
        base = read(ROOT / "infrastructure" / "deployment" / "compose.base.yaml")
        staging = read(ROOT / "infrastructure" / "deployment" / "compose.staging.yaml")
        reconcile = read(ROOT / "infrastructure" / "keycloak" / "configure-memoryos-realm.sh")

        self.assertIn("COPY --chown=keycloak:keycloak themes/memoryos /opt/keycloak/themes/memoryos", dockerfile)
        # A release directory is readable by root only; Keycloak mounting it falls back to its own look.
        self.assertNotIn("/opt/keycloak/themes/memoryos", base)
        # Staging runs the OrgMemory image, which does not carry this theme.
        self.assertIn("../keycloak/themes/memoryos:/opt/keycloak/themes/memoryos:ro", staging)
        self.assertRegex(reconcile, r'loginTheme:\s*"memoryos"')
        self.assertIn("get serverinfo", reconcile)
        self.assertNotIn("--fields themes", reconcile)
        self.assertIn("MemoryOS login theme is not available to Keycloak", reconcile)
        self.assertIn("MemoryOS realm login theme did not converge", reconcile)


if __name__ == "__main__":
    unittest.main()
