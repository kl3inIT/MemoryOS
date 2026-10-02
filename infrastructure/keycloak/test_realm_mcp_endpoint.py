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
        # A message key, so the consent page reads in the person's language.
        self.assertEqual("${knowledgeReadScopeConsentText}", scope["attributes"]["consent.screen.text"])
        # Ordered first; ChatGPT's e-mail address, then offline_access; the client's own line, unordered, last.
        self.assertEqual("10", scope["attributes"]["gui.order"])
        self.assertIn("for ordered in email:15 offline_access:20; do", SCRIPT)
        client = load("memoryos-mcp-chatgpt-client.json")
        self.assertEqual("true", client["attributes"]["display.on.consent.screen"])
        self.assertEqual("${chatgptClientConsentText}", client["attributes"]["consent.screen.text"])
        self.assertNotIn("gui.order", client["attributes"])

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

    def test_a_metadata_document_client_may_ask_for_email(self):
        # ChatGPT asks for openid, email, offline_access and the endpoint's scope; Keycloak refuses the
        # request when one is missing. Optional, so Claude, which does not ask, still gets no address.
        self.assertIn('EMAIL_SCOPE_UUID=$(find_scope_uuid email)', SCRIPT)
        self.assertIn('"realms/$TARGET_REALM/default-optional-client-scopes/$EMAIL_SCOPE_UUID"', SCRIPT)
        # Clients Keycloak already built from a document gain it as well; realm defaults reach only new ones.
        self.assertIn('select(.clientId | startswith("https://"))', SCRIPT)
        self.assertIn('"clients/$document_client/optional-client-scopes/$EMAIL_SCOPE_UUID"', SCRIPT)
        # Never a default scope of such a client.
        self.assertIn("for trimmed in profile email roles web-origins; do", SCRIPT)

    def test_clients_the_script_creates_keep_their_scopes(self):
        # Trimming the realm defaults must not change what memoryos-web and the other clients receive.
        # Naming the default scopes stops Keycloak assigning the realm's optional ones, so both are pinned.
        self.assertIn(
            """SCRIPT_CLIENT_DEFAULT_SCOPES='["acr","basic","email","profile","roles","web-origins"]'""", SCRIPT)
        self.assertIn(
            """SCRIPT_CLIENT_OPTIONAL_SCOPES='["address","microprofile-jwt","offline_access","organization","phone"]'""",
            SCRIPT)
        self.assertIn(".defaultClientScopes //= $default | .optionalClientScopes //= $optional", SCRIPT)

    def test_a_rerun_does_not_reassign_the_realm_optional_scope(self):
        # Keycloak answers a repeated assignment with 409; the end-to-end rerun stopped there once.
        self.assertIn("""jq -e --arg id "$SCOPE_UUID" 'any(.[]; .id == $id)'""", SCRIPT)

    def test_a_grant_lapses_after_thirty_days_without_use(self):
        self.assertIn("offlineSessionIdleTimeout: 2592000", SCRIPT)


class McpClientRegistrationTest(unittest.TestCase):
    def test_claude_and_chatgpt_are_admitted_by_their_metadata_documents(self):
        config = load("memoryos-mcp-client-policies.json")
        executor = config["profiles"][0]["executors"][0]
        # The image's lenient executor: ChatGPT's document has a property Keycloak 26.8 rejects (#51236).
        self.assertEqual("memoryos-client-id-metadata-document", executor["executor"])
        options = executor["configuration"]
        self.assertFalse(options["cimd-allow-http-scheme"])
        self.assertFalse(options["only-allow-confidential-client"])
        # Claude's document declares a jwt-bearer grant beside its public client (Keycloak #50362).
        self.assertTrue(options["accept-public-client-with-confidential-client-only-grant"])
        # Claude Code redirects to loopback; the hosted apps to claude.ai and chatgpt.com, and ChatGPT's
        # document names its logo on persistent.oaistatic.com. Each host is matched exactly.
        self.assertEqual({"claude.ai", "claude.com", "localhost", "127.0.0.1", "chatgpt.com", "persistent.oaistatic.com"},
                         set(options["cimd-allow-permitted-domains"]))
        # The resource allow list is filled with the endpoint at run time.
        self.assertIn('["cimd-resource-indicator-allow-list"] = [$resource]', SCRIPT)

        condition = config["policies"][0]["conditions"][0]
        self.assertEqual("client-id-uri", condition["condition"])
        self.assertEqual(["https"], condition["configuration"]["client-id-uri-scheme"])
        self.assertEqual({"claude.ai", "claude.com", "chatgpt.com"},
                         set(condition["configuration"]["client-id-uri-allow-permitted-domains"]))
        self.assertEqual(config["profiles"][0]["name"], config["policies"][0]["profiles"][0])

    def test_clients_built_from_metadata_documents_must_use_pkce(self):
        config = load("memoryos-mcp-client-policies.json")
        pkce = next(p for p in config["profiles"] if p["executors"][0]["executor"] == "pkce-enforcer")
        self.assertTrue(pkce["executors"][0]["configuration"]["auto-configure"])
        # client-id-uri votes only on the pre-authorization event, so enforcement keys on public clients
        # instead; the end-to-end run showed a metadata-document policy never enforcing (Keycloak #52795).
        policy = next(p for p in config["policies"] if p["profiles"] == [pkce["name"]])
        self.assertEqual([{"condition": "client-access-type", "configuration": {"type": ["public"]}}],
                         policy["conditions"])

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

    def test_chatgpt_shows_a_logo_the_web_app_serves(self):
        # Keycloak's consent page draws a client's logoUri; it comes from the MemoryOS origin, never a third party.
        self.assertIn('"$MEMORYOS_BROWSER_PUBLIC_URL/provider-logos/openai.svg"', SCRIPT)
        self.assertIn(".attributes.logoUri = $logo", SCRIPT)
        self.assertTrue((ROOT / "web" / "public" / "provider-logos" / "openai.svg").is_file())


class ConsentPageLanguageTest(unittest.TestCase):
    MESSAGES = KEYCLOAK / "themes" / "memoryos" / "login" / "messages"

    @staticmethod
    def keys(path):
        lines = path.read_text(encoding="utf-8").splitlines()
        return {line.split("=", 1)[0] for line in lines if "=" in line and not line.startswith("#")}

    def test_the_realm_speaks_vietnamese_first_and_english(self):
        for branch in ('verifyEmail: false,', 'verifyEmail: true,'):
            settings = SCRIPT.split(branch, 1)[1][:200]
            self.assertIn("internationalizationEnabled: true", settings)
            self.assertIn('supportedLocales: ["vi", "en"]', settings)
            self.assertIn('defaultLocale: "vi"', settings)

    def test_every_message_the_theme_overrides_exists_in_both_languages(self):
        english = self.keys(self.MESSAGES / "messages_en.properties")
        self.assertEqual(english, self.keys(self.MESSAGES / "messages_vi.properties"))
        for key in ("oauthGrantTitle", "oauthGrantRequest", "knowledgeReadScopeConsentText",
                    "offlineAccessScopeConsentText", "doYes", "doNo"):
            self.assertIn(key, english)


class KeycloakImageTest(unittest.TestCase):
    def test_the_image_is_built_with_client_id_metadata_documents(self):
        dockerfile = (KEYCLOAK / "Dockerfile").read_text(encoding="utf-8")
        build_stage = dockerfile.split("FROM keycloak AS build", 1)[1].split("RUN /opt/keycloak/bin/kc.sh build", 1)[0]
        self.assertIn("KC_FEATURES=cimd", build_stage)
        # The lenient executor is compiled against the same Keycloak and built into the server.
        self.assertIn("COPY --from=cimd-provider /memoryos-cimd.jar /opt/keycloak/providers/", build_stage)
        self.assertIn("FROM ${KEYCLOAK_IMAGE} AS keycloak", dockerfile)
        smoke = (KEYCLOAK / "smoke-test-image.sh").read_text(encoding="utf-8")
        self.assertIn('"client_id_metadata_document_supported":true', smoke)
        self.assertIn('"memoryos-client-id-metadata-document"', smoke)
        factory = (KEYCLOAK / "providers/memoryos-cimd/src/main/resources/META-INF/services"
                   / "org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory")
        self.assertEqual("io.memoryos.keycloak.cimd.LenientClientIdMetadataDocumentExecutorFactory",
                         factory.read_text(encoding="utf-8").strip())


if __name__ == "__main__":
    unittest.main()
