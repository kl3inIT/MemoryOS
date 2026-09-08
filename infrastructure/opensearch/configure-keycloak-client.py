#!/usr/bin/env python3
"""Reconcile only the Dashboards OIDC client and its inspector role scope."""
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request


def keycloak_origin(value):
    origin = value.rstrip("/")
    parsed = urllib.parse.urlsplit(origin)
    if (parsed.scheme != "https" or parsed.hostname != "auth.kl3in.tech"
            or parsed.port not in (None, 443) or parsed.username is not None
            or parsed.password is not None or parsed.path or parsed.query or parsed.fragment):
        raise ValueError("expected the trusted MemoryOS Keycloak HTTPS origin")
    return origin


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Keycloak administration must not follow redirects")


def main():
    server = keycloak_origin(os.environ["KEYCLOAK_URL"])
    client = urllib.request.build_opener(NoRedirect())
    origin = os.environ["MEMORYOS_OPENSEARCH_DASHBOARDS_PUBLIC_URL"].rstrip("/")
    parsed = urllib.parse.urlsplit(origin)
    if parsed.scheme != "https" or not parsed.hostname or parsed.path or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("Dashboards requires an exact HTTPS origin")
    secret_dir = Path(os.environ.get("MEMORYOS_OPENSEARCH_SECRET_DIRECTORY", "/apps/memoryos/secrets/opensearch"))
    login = urllib.parse.urlencode({"client_id": "admin-cli", "grant_type": "password",
                                    "username": os.environ["KEYCLOAK_ADMIN_USERNAME"],
                                    "password": os.environ["KC_CLI_PASSWORD"]}).encode()
    with client.open(urllib.request.Request(server + "/realms/master/protocol/openid-connect/token", data=login), timeout=20) as response:
        token = json.load(response)["access_token"]

    def api(method, path, body=None):
        request = urllib.request.Request(server + "/admin/realms/memoryos/" + path, method=method,
                                         data=None if body is None else json.dumps(body).encode(),
                                         headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        with client.open(request, timeout=20) as response:
            data = response.read()
            return json.loads(data) if data else None

    client_id = "memoryos-opensearch-dashboards"
    clients = api("GET", "clients?clientId=" + client_id)
    if len(clients) > 1:
        raise RuntimeError("duplicate Dashboards client")
    role = api("GET", "roles/memoryos-inspector")
    client_definition = {
        "clientId": client_id, "name": "MemoryOS OpenSearch Dashboards", "protocol": "openid-connect", "enabled": True,
        "publicClient": False, "clientAuthenticatorType": "client-secret", "standardFlowEnabled": True,
        "implicitFlowEnabled": False, "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False,
        "fullScopeAllowed": False, "rootUrl": origin, "baseUrl": "/", "redirectUris": [origin + "/auth/openid/login"],
        "webOrigins": [origin], "attributes": {"post.logout.redirect.uris": origin + "/"},
        "defaultClientScopes": ["profile", "email"], "optionalClientScopes": [],
        "secret": (secret_dir / "oidc-client-secret.txt").read_text().strip(),
        "protocolMappers": [
            {"name": "memoryos-search-inspector-roles", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-realm-role-mapper",
             "config": {"claim.name": "memoryos_roles", "jsonType.label": "String", "multivalued": "true", "access.token.claim": "true", "id.token.claim": "true"}},
            {"name": "memoryos-search-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
             "config": {"included.custom.audience": client_id, "access.token.claim": "true", "id.token.claim": "true"}},
        ],
    }
    if clients:
        api("PUT", "clients/" + clients[0]["id"], client_definition)
    else:
        api("POST", "clients", client_definition)
        clients = api("GET", "clients?clientId=" + client_id)
    identifier = clients[0]["id"]
    scope_path = "clients/" + identifier + "/scope-mappings/realm"
    current_roles = api("GET", scope_path)
    excess = [item for item in current_roles if item["name"] != "memoryos-inspector"]
    if excess:
        api("DELETE", scope_path, excess)
    api("POST", scope_path, [{"id": role["id"], "name": role["name"]}])
    confirmed = api("GET", "clients/" + identifier)
    assert confirmed["redirectUris"] == client_definition["redirectUris"] and not confirmed["fullScopeAllowed"]
    assert [item["name"] for item in api("GET", scope_path)] == ["memoryos-inspector"]
    print("Dashboards OIDC client reconciled; only memoryos-inspector role scoped; no user roles changed")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Keycloak reconciliation failed: " + type(error).__name__ + " status=" + str(getattr(error, "code", "unknown")))
        raise SystemExit(1) from None
