#!/usr/bin/env python3
"""Reconcile only the MemoryOS Grafana OIDC client; never grant user roles."""
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def required(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"{name} is required")
    return value


def origin(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1"})) or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment or "*" in value:
        raise ValueError("URL must be an exact HTTPS origin (HTTP allowed only on loopback)")
    return value


def reconcile():
    # Never forward administrator credentials or bearer tokens through redirects.
    opener = urllib.request.build_opener(RejectRedirects())
    server = origin(required("KEYCLOAK_URL"))
    public = origin(required("MEMORYOS_GRAFANA_PUBLIC_URL"))
    secret = Path(required("MEMORYOS_GRAFANA_OIDC_SECRET_FILE")).read_text().strip()
    if len(secret) < 32:
        raise ValueError("Grafana client secret must contain at least 32 characters")
    credentials = urllib.parse.urlencode({
        "client_id": "admin-cli", "grant_type": "password",
        "username": required("KEYCLOAK_ADMIN_USERNAME"),
        "password": required("KC_CLI_PASSWORD"),
    }).encode()
    admin_realm = urllib.parse.quote(os.environ.get("KEYCLOAK_ADMIN_REALM", "master"), safe="")
    request = urllib.request.Request(server + f"/realms/{admin_realm}/protocol/openid-connect/token", credentials)
    with opener.open(request, timeout=15) as response:
        token = json.load(response)["access_token"]

    def api(method, path, body=None):
        request = urllib.request.Request(server + "/admin/realms/memoryos/" + path,
            None if body is None else json.dumps(body).encode(),
            {"Authorization": "Bearer " + token, "Content-Type": "application/json"}, method=method)
        with opener.open(request, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    # The existing realm provisioning owns this role and the owner assignment.
    role = api("GET", "roles/memoryos-inspector")
    if role.get("composite"):
        raise ValueError("memoryos-inspector must not be a composite role")
    desired = {
        "clientId": "memoryos-grafana", "name": "MemoryOS Grafana", "enabled": True,
        "protocol": "openid-connect", "publicClient": False,
        "clientAuthenticatorType": "client-secret", "secret": secret,
        "standardFlowEnabled": True, "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False,
        "fullScopeAllowed": False, "rootUrl": public, "baseUrl": "/",
        "redirectUris": [public + "/login/generic_oauth"], "webOrigins": [public],
        "attributes": {"pkce.code.challenge.method": "S256"},
        "defaultClientScopes": ["profile", "email"], "optionalClientScopes": [],
    }
    clients = api("GET", "clients?clientId=memoryos-grafana")
    if len(clients) > 1:
        raise ValueError("Duplicate memoryos-grafana clients")
    if clients:
        api("PUT", "clients/" + clients[0]["id"], desired)
    else:
        api("POST", "clients", desired)
    client = api("GET", "clients?clientId=memoryos-grafana")[0]
    path = "clients/" + client["id"]
    # Reconcile assigned scopes explicitly: PUT alone does not remove old scopes.
    for kind, allowed in [("default", {"profile", "email"}), ("optional", set())]:
        for scope in api("GET", path + f"/{kind}-client-scopes"):
            if scope["name"] not in allowed:
                api("DELETE", path + f"/{kind}-client-scopes/" + scope["id"])
    existing_roles = api("GET", path + "/scope-mappings/realm")
    unwanted = [item for item in existing_roles if item["id"] != role["id"]]
    if unwanted:
        api("DELETE", path + "/scope-mappings/realm", unwanted)
    api("POST", path + "/scope-mappings/realm", [{"id": role["id"], "name": role["name"]}])
    mapper = {
        "name": "memoryos-inspector", "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-realm-role-mapper", "consentRequired": False,
        "config": {"claim.name": "realm_access.roles", "jsonType.label": "String",
            "multivalued": "true", "id.token.claim": "true", "access.token.claim": "true",
            "userinfo.token.claim": "true"},
    }
    for existing in api("GET", path + "/protocol-mappers/models"):
        api("DELETE", path + "/protocol-mappers/models/" + existing["id"])
    api("POST", path + "/protocol-mappers/models", mapper)
    actual = api("GET", path)
    if actual["redirectUris"] != desired["redirectUris"] or actual["fullScopeAllowed"]:
        raise ValueError("Grafana callback/scope reconciliation failed")
    if {r["name"] for r in api("GET", path + "/scope-mappings/realm")} != {"memoryos-inspector"}:
        raise ValueError("Grafana realm role scope reconciliation failed")
    print("client=memoryos-grafana reconciled; role scope=memoryos-inspector; user grants unchanged")


if __name__ == "__main__":
    try:
        reconcile()
    except urllib.error.HTTPError as error:
        # Error bodies can contain credentials or tokens; do not print them.
        sys.exit(f"Keycloak request failed (HTTP {error.code})")
    except (ValueError, OSError, KeyError, AssertionError):
        sys.exit("Grafana SSO provisioning failed; verify required environment, secret file and realm contract")
