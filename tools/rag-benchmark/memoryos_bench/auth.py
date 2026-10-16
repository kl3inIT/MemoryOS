"""Role sign-in with Keycloak offline tokens.

A person signs in once per role in a browser (Authorization Code with PKCE through the public
`memoryos-integration` client and its loopback redirect) and the offline refresh token is kept in a
token store outside the repository. Every later run mints access tokens from it; an offline session
lasts 30 days without use, so a role signs in again only after a month of silence.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

CLIENT_ID = "memoryos-integration"
REDIRECT_PORT = 8765
REDIRECT_URI = f"http://127.0.0.1:{REDIRECT_PORT}/callback"
# Renew this long before the access token expires, so a request never races the expiry.
EARLY_SECONDS = 30.0


class AuthError(RuntimeError):
    """A role cannot be authenticated; the message says what to do."""


class TokenStore:
    """Offline refresh tokens per role, in one JSON file only its owner reads."""

    def __init__(self, path: Path) -> None:
        self.path = path

    def load(self) -> dict:
        if not self.path.exists():
            return {}
        return json.loads(self.path.read_text(encoding="utf-8"))

    def save_role(self, role: str, issuer: str, refresh_token: str) -> None:
        data = self.load()
        data[role] = {"issuer": issuer, "refreshToken": refresh_token}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        temporary.replace(self.path)

    def role(self, role: str) -> dict:
        entry = self.load().get(role)
        if not entry:
            raise AuthError(
                f"role {role!r} has not signed in; run"
                f" `python -m memoryos_bench login --role {role}`"
            )
        return entry


def _token_endpoint(issuer: str) -> str:
    return issuer.rstrip("/") + "/protocol/openid-connect/token"


def _post_form(url: str, form: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=urllib.parse.urlencode(form).encode(),
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        body = error.read().decode(errors="replace")[:300]
        raise AuthError(f"token endpoint answered {error.code}: {body}") from error


class RoleSession:
    """Access tokens for one role, renewed from its offline refresh token."""

    def __init__(self, store: TokenStore, role: str) -> None:
        self._store = store
        self._role = role
        self._access = ""
        self._expires = 0.0
        self._lock = threading.Lock()

    def bearer(self) -> str:
        with self._lock:
            if not self._access or time.monotonic() >= self._expires:
                self._renew()
            return self._access

    def invalidate(self) -> None:
        with self._lock:
            self._access = ""

    def _renew(self) -> None:
        entry = self._store.role(self._role)
        tokens = _post_form(
            _token_endpoint(entry["issuer"]),
            {
                "grant_type": "refresh_token",
                "client_id": CLIENT_ID,
                "refresh_token": entry["refreshToken"],
            },
        )
        self._access = tokens["access_token"]
        self._expires = time.monotonic() + float(tokens.get("expires_in", 300)) - EARLY_SECONDS
        if tokens.get("refresh_token") and tokens["refresh_token"] != entry["refreshToken"]:
            self._store.save_role(self._role, entry["issuer"], tokens["refresh_token"])


def login(store: TokenStore, role: str, issuer: str) -> None:
    """Signs a role in through the browser and stores its offline refresh token."""
    verifier = secrets.token_urlsafe(64)
    challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    )
    state = secrets.token_urlsafe(24)
    received: dict = {}

    class Callback(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 (http.server naming)
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            received.update({key: values[0] for key, values in query.items()})
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Signed in. You can close this tab.")

        def log_message(self, *_: object) -> None:
            return None

    try:
        server = HTTPServer(("127.0.0.1", REDIRECT_PORT), Callback)
    except OSError as error:
        raise AuthError(
            f"port {REDIRECT_PORT} is busy (another sign-in still waiting?); close it and retry"
        ) from error
    url = (
        issuer.rstrip("/")
        + "/protocol/openid-connect/auth?"
        + urllib.parse.urlencode(
            {
                "client_id": CLIENT_ID,
                "response_type": "code",
                "redirect_uri": REDIRECT_URI,
                "scope": "openid offline_access",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
                "state": state,
                "prompt": "login",
            }
        )
    )
    print(f"Sign in as the account of role {role!r}:\n{url}")
    webbrowser.open(url)
    while "code" not in received and "error" not in received:
        server.handle_request()
    server.server_close()
    if received.get("state") != state:
        raise AuthError("the sign-in answered with another state; start again")
    if "error" in received:
        raise AuthError(
            f"sign-in refused: {received.get('error')} {received.get('error_description', '')}"
        )
    tokens = _post_form(
        _token_endpoint(issuer),
        {
            "grant_type": "authorization_code",
            "client_id": CLIENT_ID,
            "code": received["code"],
            "redirect_uri": REDIRECT_URI,
            "code_verifier": verifier,
        },
    )
    if "offline_access" not in tokens.get("scope", "").split():
        raise AuthError(
            "Keycloak did not grant offline_access; the token would expire with the browser session"
        )
    store.save_role(role, issuer, tokens["refresh_token"])
    print(f"role {role!r} signed in; offline token stored in {store.path}")
