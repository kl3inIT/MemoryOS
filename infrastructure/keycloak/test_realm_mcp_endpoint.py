"""MEM-114: the realm reconciliation for the MemoryOS MCP endpoint."""

from pathlib import Path
import json
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
KEYCLOAK = ROOT / "infrastructure" / "keycloak"
SCRIPT = (KEYCLOAK / "configure-memoryos-realm.sh").read_text(encoding="utf-8")


def load(name):
    return json.loads((KEYCLOAK / name).read_text(encoding="utf-8"))


class McpEndpointIsOptionalTest(unittest.TestCase):
    def test_an_environment_without_the_endpoint_is_untouched(self):
        # Neither variable is required, and everything the endpoint changes sits behind its switch, so
        # a realm reconciled without it keeps its default scopes and gains no MCP client.
        for name in ("MEMORYOS_MCP_ENDPOINT_URL", "MEMORYOS_MCP_CHATGPT_CLIENT_SECRET"):
            self.assertNotIn("%s:?" % name, SCRIPT, name)
        switched = SCRIPT.split('if [ "$MCP_ENABLED" = true ]; then', 1)[1]
        self.assertIn("configure_mcp_endpoint", switched)
        self.assertIn("upsert_client memoryos-chatgpt", switched)
        self.assertNotIn("upsert_client memoryos-chatgpt", SCRIPT.split('if [ "$MCP_ENABLED" = true ]; then', 1)[0])

    def test_the_endpoint_is_the_browser_origin_followed_by_mcp(self):
        # The token audience, the protected-resource metadata and the URL people paste must be one string.
        self.assertIn('"$MEMORYOS_MCP_ENDPOINT_URL" != "$MEMORYOS_BROWSER_PUBLIC_URL/mcp"', SCRIPT)
        self.assertIn("MEMORYOS_MCP_ENDPOINT_URL must be the browser origin followed by /mcp", SCRIPT)

    def test_a_chatgpt_secret_without_the_endpoint_is_refused(self):
        self.assertIn("MEMORYOS_MCP_CHATGPT_CLIENT_SECRET needs MEMORYOS_MCP_ENDPOINT_URL", SCRIPT)


class McpEndpointTokenTest(unittest.TestCase):
    def test_the_scope_carries_the_endpoint_audience_and_shows_on_consent(self):
        scope = load("memoryos-mcp-knowledge-scope.json")
        self.assertEqual("knowledge:read", scope["name"])
        self.assertEqual("true", scope["attributes"]["display.on.consent.screen"])
        self.assertEqual("true", scope["attributes"]["include.in.token.scope"])

        mapper = load("memoryos-mcp-audience-mapper.json")
        self.assertEqual("oidc-audience-mapper", mapper["protocolMapper"])
        self.assertEqual("true", mapper["config"]["access.token.claim"])
        self.assertEqual("false", mapper["config"]["id.token.claim"])
        # Filled from MEMORYOS_MCP_ENDPOINT_URL at run time, never a fixed host.
        self.assertEqual("", mapper["config"]["included.custom.audience"])
        self.assertIn('.config["included.custom.audience"] = $audience', SCRIPT)

    def test_mcp_tokens_carry_no_profile_email_or_roles(self):
        # A client built from a metadata document gets the realm's default scopes; the endpoint needs
        # only `sub`, so those are trimmed and checked after the trim.
        self.assertIn("for trimmed in profile email roles web-origins; do", SCRIPT)
        self.assertIn("realm default client scopes did not converge", SCRIPT)

    def test_clients_the_script_creates_keep_their_scopes(self):
        # Trimming the realm defaults must not change what memoryos-web and the other clients receive.
        # Naming the default scopes stops Keycloak assigning the realm's optional ones, so both are pinned.
        self.assertIn(
            """SCRIPT_CLIENT_DEFAULT_SCOPES='["acr","basic","email","profile","roles","web-origins"]'""", SCRIPT)
        self.assertIn(
            """SCRIPT_CLIENT_OPTIONAL_SCOPES='["address","microprofile-jwt","offline_access","organization","phone"]'""",
            SCRIPT)
        self.assertIn(".defaultClientScopes //= $default | .optionalClientScopes //= $optional", SCRIPT)

    def test_a_grant_lapses_after_thirty_days_without_use(self):
        self.assertIn("offlineSessionIdleTimeout: 2592000", SCRIPT)


class McpClientRegistrationTest(unittest.TestCase):
    def test_claude_is_admitted_by_its_metadata_document(self):
        config = load("memoryos-mcp-client-policies.json")
        executor = config["profiles"][0]["executors"][0]
        self.assertEqual("client-id-metadata-document", executor["executor"])
        options = executor["configuration"]
        self.assertFalse(options["cimd-allow-http-scheme"])
        self.assertFalse(options["only-allow-confidential-client"])
        # Claude's document declares a jwt-bearer grant beside its public client (Keycloak #50362).
        self.assertTrue(options["accept-public-client-with-confidential-client-only-grant"])
        # Claude Code redirects to loopback; the hosted apps to claude.ai.
        self.assertEqual({"claude.ai", "claude.com", "localhost", "127.0.0.1"},
                         set(options["cimd-allow-permitted-domains"]))
        # The resource allow list is filled with the endpoint at run time.
        self.assertIn('["cimd-resource-indicator-allow-list"] = [$resource]', SCRIPT)

        condition = config["policies"][0]["conditions"][0]
        self.assertEqual("client-id-uri", condition["condition"])
        self.assertEqual(["https"], condition["configuration"]["client-id-uri-scheme"])
        # ChatGPT's document is one Keycloak cannot read; it must not be tried.
        self.assertNotIn("chatgpt.com", condition["configuration"]["client-id-uri-allow-permitted-domains"])
        self.assertEqual(config["profiles"][0]["name"], config["policies"][0]["profiles"][0])

    def test_clients_built_from_metadata_documents_must_use_pkce(self):
        config = load("memoryos-mcp-client-policies.json")
        pkce = next(p for p in config["profiles"] if p["executors"][0]["executor"] == "pkce-enforcer")
        self.assertTrue(pkce["executors"][0]["configuration"]["auto-configure"])
        # A policy of its own (Keycloak #52795), matching the same metadata documents.
        policy = next(p for p in config["policies"] if p["profiles"] == [pkce["name"]])
        self.assertEqual(config["policies"][0]["conditions"], policy["conditions"])

    def test_other_client_policies_of_the_realm_survive(self):
        self.assertRegex(SCRIPT, re.compile(r"merge_client_policies profiles\s+merge_client_policies policies"))

    def test_chatgpt_has_one_exact_callback_and_only_the_endpoint_scope(self):
        client = load("memoryos-mcp-chatgpt-client.json")
        self.assertFalse(client["publicClient"])
        self.assertTrue(client["consentRequired"])
        self.assertFalse(client["fullScopeAllowed"])
        self.assertFalse(client["directAccessGrantsEnabled"])
        self.assertEqual(["https://chatgpt.com/connector_platform_oauth_redirect"], client["redirectUris"])
        self.assertEqual("S256", client["attributes"]["pkce.code.challenge.method"])
        self.assertIn("reconcile_client_scopes default acr basic knowledge:read", SCRIPT)
        self.assertIn("reconcile_client_scopes optional offline_access", SCRIPT)


class KeycloakImageTest(unittest.TestCase):
    def test_the_image_is_built_with_client_id_metadata_documents(self):
        dockerfile = (KEYCLOAK / "Dockerfile").read_text(encoding="utf-8")
        build_stage = dockerfile.split("FROM ${KEYCLOAK_IMAGE} AS build", 1)[1].split("RUN /opt/keycloak/bin/kc.sh build", 1)[0]
        self.assertIn("KC_FEATURES=cimd", build_stage)
        smoke = (KEYCLOAK / "smoke-test-image.sh").read_text(encoding="utf-8")
        self.assertIn('"client_id_metadata_document_supported":true', smoke)


if __name__ == "__main__":
    unittest.main()
