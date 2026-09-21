import base64
import hashlib
import json
import socket
import threading
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from rag_benchmark.auth import (
    AuthError,
    RefreshToken,
    TokenStore,
    account_of,
    authorization_url,
    login,
    pkce_pair,
)

TOKEN_URL = "https://auth.test/realms/memoryos/protocol/openid-connect/token"  # noqa: S105


def jwt(claims: dict[str, object]) -> str:
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    return f"header.{payload}.signature"


def test_the_pkce_challenge_is_the_s256_digest_of_the_verifier() -> None:
    verifier, challenge = pkce_pair()
    expected = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=")

    assert challenge == expected.decode()
    assert 43 <= len(verifier) <= 128


def test_every_login_asks_for_credentials_so_actors_never_share_a_browser_session() -> None:
    url = authorization_url(
        "https://auth.test/realms/memoryos",
        "memoryos-integration",
        "http://127.0.0.1:8765/callback",
        "challenge",
        "state",
    )
    query = parse_qs(urlparse(url).query)

    assert query["prompt"] == ["login"]
    assert query["code_challenge_method"] == ["S256"]
    assert query["redirect_uri"] == ["http://127.0.0.1:8765/callback"]


def test_the_signed_in_account_is_read_from_the_token() -> None:
    assert account_of(jwt({"email": "exec@memoryos.test"})) == "exec@memoryos.test"
    assert account_of("not-a-token") == ""


def test_a_stored_login_renews_access_tokens_and_keeps_the_rotated_refresh_token(
    tmp_path: Path,
) -> None:
    store = TokenStore(tmp_path / "tokens.json")
    store.save("exec", "refresh-1", "exec@memoryos.test")
    issued: list[str] = []

    def keycloak(request: httpx.Request) -> httpx.Response:
        form = parse_qs(request.content.decode())
        assert form["grant_type"] == ["refresh_token"]
        issued.append(form["refresh_token"][0])
        number = len(issued)
        return httpx.Response(
            200,
            json={
                "access_token": f"access-{number}",
                "expires_in": 300,
                "refresh_token": f"refresh-{number + 1}",
            },
        )

    now = [0.0]
    with httpx.Client(transport=httpx.MockTransport(keycloak)) as http:
        tokens = RefreshToken(
            http, TOKEN_URL, "memoryos-integration", store, "exec", clock=lambda: now[0]
        )
        first = tokens.bearer()
        now[0] = 200.0  # still valid: renewal happens 30 seconds before the 300-second expiry
        cached = tokens.bearer()
        now[0] = 271.0
        renewed = tokens.bearer()

    assert (first, cached, renewed) == ("access-1", "access-1", "access-2")
    assert issued == ["refresh-1", "refresh-2"]
    assert store.refresh_token("exec") == "refresh-3"
    assert store.account("exec") == "exec@memoryos.test"


def test_an_expired_login_says_to_sign_in_again(tmp_path: Path) -> None:
    store = TokenStore(tmp_path / "tokens.json")
    store.save("exec", "stale", "")
    transport = httpx.MockTransport(lambda _: httpx.Response(400, json={"error": "invalid_grant"}))
    with httpx.Client(transport=transport) as http:
        tokens = RefreshToken(http, TOKEN_URL, "memoryos-integration", store, "exec")
        with pytest.raises(AuthError, match="login --actor exec"):
            tokens.bearer()


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def test_login_exchanges_the_callback_code_with_its_verifier_and_stores_the_refresh_token(
    tmp_path: Path,
) -> None:
    port = free_port()
    exchanged: dict[str, list[str]] = {}

    def keycloak(request: httpx.Request) -> httpx.Response:
        exchanged.update(parse_qs(request.content.decode()))
        return httpx.Response(
            200,
            json={
                "access_token": jwt({"email": "finance@memoryos.test"}),
                "expires_in": 300,
                "refresh_token": "refresh-finance",
            },
        )

    def browser(url: str) -> None:
        state = parse_qs(urlparse(url).query)["state"][0]
        callback = f"http://127.0.0.1:{port}/callback?code=the-code&state={state}"
        threading.Timer(0.2, lambda: httpx.get(callback, timeout=5)).start()

    store = TokenStore(tmp_path / "tokens.json")
    with httpx.Client(transport=httpx.MockTransport(keycloak)) as http:
        account = login(
            "https://auth.test/realms/memoryos",
            "memoryos-integration",
            port,
            "finance",
            store,
            http,
            open_browser=browser,
            timeout_seconds=10,
        )

    assert account == "finance@memoryos.test"
    assert store.refresh_token("finance") == "refresh-finance"
    assert exchanged["grant_type"] == ["authorization_code"]
    assert exchanged["code"] == ["the-code"]
    assert exchanged["redirect_uri"] == [f"http://127.0.0.1:{port}/callback"]
    assert exchanged["code_verifier"][0]


def test_login_refuses_a_port_another_login_still_holds(tmp_path: Path) -> None:
    with socket.socket() as held:
        held.bind(("127.0.0.1", 0))
        held.listen()
        port = int(held.getsockname()[1])
        with (
            httpx.Client() as http,
            pytest.raises(AuthError, match=f"port {port} is in use"),
        ):
            login(
                "https://auth.test/realms/memoryos",
                "memoryos-integration",
                port,
                "exec",
                TokenStore(tmp_path / "tokens.json"),
                http,
                open_browser=lambda _url: None,
                timeout_seconds=1,
            )
