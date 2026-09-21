"""
Actor authentication. The realm enables no password grant and access tokens live five minutes,
while a run takes much longer, so every credential here can mint fresh access tokens:

- `login`: Authorization Code with PKCE through the public `memoryos-integration` client and its
  loopback redirect. A person signs in once per actor in the browser; the refresh token is kept in a
  file outside the repository and renewed while the run lasts;
- client credentials of a service-account client bound to the actor;
- a bearer token captured for one short run.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import threading
import time
import webbrowser
from collections.abc import Callable
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Protocol
from urllib.parse import parse_qs, urlencode, urlparse

import httpx

# Renew this long before the access token expires, so a request never races the expiry.
_EARLY_SECONDS = 30.0


class AuthError(RuntimeError):
    """An actor cannot be authenticated; the message says what to do."""


class TokenSource(Protocol):
    def bearer(self) -> str: ...

    def invalidate(self) -> None: ...


class StaticToken:
    """A bearer token captured for one run; it cannot be renewed."""

    def __init__(self, token: str) -> None:
        self._token = token

    def bearer(self) -> str:
        return self._token

    def invalidate(self) -> None:
        raise AuthError("the captured bearer token expired; use `rag-benchmark login` instead")


@dataclass
class _Held:
    access_token: str = ""
    expires_at: float = 0.0


class _Renewing:
    """An access token renewed on demand, shared by one actor's requests."""

    def __init__(self, clock: Callable[[], float]) -> None:
        self._clock = clock
        self._held = _Held()
        self._lock = threading.Lock()

    def bearer(self) -> str:
        with self._lock:
            if not self._held.access_token or self._clock() >= self._held.expires_at:
                token, lifetime = self._renew()
                self._held = _Held(token, self._clock() + max(1.0, lifetime - _EARLY_SECONDS))
            return self._held.access_token

    def invalidate(self) -> None:
        with self._lock:
            self._held = _Held()

    def _renew(self) -> tuple[str, float]:
        raise NotImplementedError


class ClientCredentials(_Renewing):
    def __init__(
        self,
        http: httpx.Client,
        token_url: str,
        client_id: str,
        client_secret: str,
        label: str,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        super().__init__(clock)
        self._http, self._url, self._label = http, token_url, label
        self._id, self._secret = client_id, client_secret

    def _renew(self) -> tuple[str, float]:
        body = _token_request(
            self._http,
            self._url,
            {
                "grant_type": "client_credentials",
                "client_id": self._id,
                "client_secret": self._secret,
            },
            self._label,
        )
        return body.access_token, body.expires_in


class RefreshToken(_Renewing):
    """Renews through the refresh token `login` stored; a rotated refresh token is kept again."""

    def __init__(
        self,
        http: httpx.Client,
        token_url: str,
        client_id: str,
        store: TokenStore,
        label: str,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        super().__init__(clock)
        self._http, self._url, self._id = http, token_url, client_id
        self._store, self._label = store, label

    def _renew(self) -> tuple[str, float]:
        refresh = self._store.refresh_token(self._label)
        if not refresh:
            raise AuthError(
                f"{self._label}: not signed in; run `rag-benchmark login --actor {self._label}`"
            )
        body = _token_request(
            self._http,
            self._url,
            {"grant_type": "refresh_token", "client_id": self._id, "refresh_token": refresh},
            self._label,
            expired_hint=True,
        )
        if body.refresh_token and body.refresh_token != refresh:
            self._store.save(self._label, body.refresh_token, self._store.account(self._label))
        return body.access_token, body.expires_in


class TokenStore:
    """
    Refresh tokens per actor label, in a file outside the repository that only the current user
    may read.
    Only the account hint (the signed-in email) is kept beside the token, never a password.
    """

    def __init__(self, path: Path) -> None:
        self._path = path

    @property
    def path(self) -> Path:
        return self._path

    def refresh_token(self, label: str) -> str | None:
        entry = self._read().get(label)
        token = entry.get("refresh_token") if isinstance(entry, dict) else None
        return token if isinstance(token, str) and token else None

    def account(self, label: str) -> str:
        entry = self._read().get(label)
        account = entry.get("account") if isinstance(entry, dict) else None
        return account if isinstance(account, str) else ""

    def save(self, label: str, refresh_token: str, account: str) -> None:
        rows = self._read()
        rows[label] = {"refresh_token": refresh_token, "account": account}
        self._path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._path.with_suffix(".tmp")
        temporary.write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
        _owner_only(temporary)
        temporary.replace(self._path)

    def _read(self) -> dict[str, object]:
        if not self._path.exists():
            return {}
        rows = json.loads(self._path.read_text(encoding="utf-8"))
        return rows if isinstance(rows, dict) else {}


@dataclass(frozen=True)
class _TokenBody:
    access_token: str
    expires_in: float
    refresh_token: str | None


def _token_request(
    http: httpx.Client,
    url: str,
    form: dict[str, str],
    label: str,
    expired_hint: bool = False,
) -> _TokenBody:
    response = http.post(url, data=form)
    if response.status_code != 200:
        hint = f"; run `rag-benchmark login --actor {label}` again" if expired_hint else ""
        raise AuthError(f"{label}: token request failed ({response.status_code}){hint}")
    body = response.json()
    token = body.get("access_token")
    if not isinstance(token, str) or not token:
        raise AuthError(f"{label}: token response carried no access token")
    refresh = body.get("refresh_token")
    return _TokenBody(
        access_token=token,
        expires_in=float(body.get("expires_in", 60)),
        refresh_token=refresh if isinstance(refresh, str) and refresh else None,
    )


# Login ----------------------------------------------------------------------------------------


def pkce_pair() -> tuple[str, str]:
    """A PKCE verifier and its S256 challenge (RFC 7636)."""
    verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return verifier, base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


def authorization_url(
    issuer: str, client_id: str, redirect_uri: str, challenge: str, state: str
) -> str:
    query = urlencode(
        {
            "client_id": client_id,
            "response_type": "code",
            "scope": "openid",
            "redirect_uri": redirect_uri,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state,
            # Every actor is a different account, so an existing browser session must not be reused.
            "prompt": "login",
        }
    )
    return f"{issuer}/protocol/openid-connect/auth?{query}"


def account_of(access_token: str) -> str:
    """The signed-in account, read from the token only to show whom the actor became."""
    try:
        payload = access_token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    except (IndexError, ValueError):
        return ""
    value = claims.get("email") or claims.get("preferred_username") or ""
    return str(value)


def login(
    issuer: str,
    client_id: str,
    port: int,
    label: str,
    store: TokenStore,
    http: httpx.Client,
    open_browser: Callable[[str], object] = webbrowser.open,
    timeout_seconds: float = 300.0,
) -> str:
    """Signs one actor in through the browser and stores its refresh token; returns the account."""
    verifier, challenge = pkce_pair()
    state = secrets.token_urlsafe(24)
    redirect_uri = f"http://127.0.0.1:{port}/callback"
    received: dict[str, str] = {}

    class Callback(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - the http.server contract
            url = urlparse(self.path)
            if url.path != "/callback":
                self.send_response(404)
                self.end_headers()
                return
            for key, values in parse_qs(url.query).items():
                received[key] = values[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write("Đã nhận đăng nhập. Có thể đóng tab này.".encode())

        def log_message(self, *_: object) -> None:
            return

    try:
        server = _ExclusiveServer(("127.0.0.1", port), Callback)
    except OSError as busy:
        raise AuthError(
            f"{label}: port {port} is in use; another login may still be waiting"
        ) from busy
    server.timeout = 1.0
    try:
        url = authorization_url(issuer, client_id, redirect_uri, challenge, state)
        # ASCII only: a Windows console may not encode Vietnamese.
        print(f"Sign in as the account of actor '{label}' in the browser:\n{url}", flush=True)
        open_browser(url)
        deadline = time.monotonic() + timeout_seconds
        while "code" not in received and "error" not in received:
            if time.monotonic() > deadline:
                raise AuthError(f"{label}: no sign-in within {int(timeout_seconds)} seconds")
            server.handle_request()
    finally:
        server.server_close()
    if "error" in received:
        raise AuthError(f"{label}: sign-in refused ({received['error']})")
    if not secrets.compare_digest(received.get("state", ""), state):
        raise AuthError(f"{label}: the sign-in response does not belong to this login")
    body = _token_request(
        http,
        f"{issuer}/protocol/openid-connect/token",
        {
            "grant_type": "authorization_code",
            "client_id": client_id,
            "code": received["code"],
            "redirect_uri": redirect_uri,
            "code_verifier": verifier,
        },
        label,
    )
    if not body.refresh_token:
        raise AuthError(f"{label}: the realm returned no refresh token")
    account = account_of(body.access_token)
    store.save(label, body.refresh_token, account)
    return account


class _ExclusiveServer(HTTPServer):
    # HTTPServer reuses addresses, which on Windows lets a second login bind the same port and lose
    # the callback to a stale one. The redirect port must belong to exactly one login.
    allow_reuse_address = False


def _owner_only(path: Path) -> None:
    # POSIX only; on Windows the file lives under the user's profile, which already restricts it.
    if os.name == "posix":
        path.chmod(0o600)
