"""
MemoryOS HTTP access for one actor: a bearer token, the search page and a Chat turn read from saved
history.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import httpx

from .config import Actor, Config


class BenchmarkError(RuntimeError):
    """The run cannot continue: authentication, authorization or a reply that never finished."""


@dataclass
class Reply:
    """One finished assistant message, as history saved it."""

    message_id: str
    status: str
    content: str
    document_ids: list[str]
    steps: int
    seconds: float


class ActorClient:
    """Every request carries one actor's token; nothing here shares state between actors."""

    def __init__(self, config: Config, actor: Actor) -> None:
        self._config = config
        self._actor = actor
        self._http = httpx.Client(base_url=config.base_url, timeout=config.timeout_seconds)
        self._token = actor.token or self._client_credentials_token()

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> ActorClient:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def _client_credentials_token(self) -> str:
        assert self._actor.client_id and self._actor.client_secret  # noqa: S101 - checked in config
        response = httpx.post(
            f"{self._config.issuer}/protocol/openid-connect/token",
            data={
                "grant_type": "client_credentials",
                "client_id": self._actor.client_id,
                "client_secret": self._actor.client_secret,
            },
            timeout=self._config.timeout_seconds,
        )
        if response.status_code != 200:
            raise BenchmarkError(
                f"{self._actor.label}: token request failed ({response.status_code})"
            )
        token = response.json().get("access_token")
        if not isinstance(token, str) or not token:
            raise BenchmarkError(f"{self._actor.label}: token response carried no access token")
        return token

    def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:  # noqa: ANN401
        # The bearer filter chain disables CSRF; the header is what the browser sends and costs
        # nothing here.
        headers = {
            "Authorization": f"Bearer {self._token}",
            "X-MemoryOS-CSRF": "1",
            **kwargs.pop("headers", {}),
        }
        response = self._http.request(method, path, headers=headers, **kwargs)
        if response.status_code in (401, 403):
            raise BenchmarkError(f"{self._actor.label}: {path} refused ({response.status_code})")
        return response

    # Retrieval -----------------------------------------------------------------

    def search(self, query: str, limit: int) -> list[dict[str, Any]]:
        """Document results in rank order, paginated because one page carries at most twenty."""
        results: list[dict[str, Any]] = []
        page = 0
        while len(results) < limit and page <= 49:
            response = self._request(
                "POST",
                "/api/search",
                json={"query": query, "page": page, "pageSize": self._config.search_page_size},
            )
            if response.status_code != 200:
                raise BenchmarkError(f"search failed ({response.status_code}) for {query!r}")
            body = response.json()
            results.extend(body.get("results", []))
            if not body.get("hasMore"):
                break
            page += 1
        return results[:limit]

    # Chat ----------------------------------------------------------------------

    def ask(self, question: str) -> Reply:
        """One question in its own session, read from saved history so no event stream is parsed."""
        session = self._request("POST", "/api/chat/sessions", json={"title": None})
        if session.status_code not in (200, 201):
            raise BenchmarkError(f"session creation failed ({session.status_code})")
        session_body = session.json()
        session_id = session_body["id"]
        try:
            return self._turn(session_id, session_body["rootMessageId"], question)
        finally:
            self._request("DELETE", f"/api/chat/sessions/{session_id}")

    def _turn(self, session_id: str, parent_message_id: str, question: str) -> Reply:
        started = time.monotonic()
        accepted = self._request(
            "POST",
            f"/api/chat/sessions/{session_id}/messages",
            json={"parentMessageId": parent_message_id, "text": question},
        )
        if accepted.status_code != 202:
            raise BenchmarkError(f"message rejected ({accepted.status_code})")
        body = accepted.json()
        assistant_id, user_id = body["assistantMessageId"], body["userMessageId"]
        deadline = started + self._config.reply_timeout_seconds
        while True:
            history = self._request(
                "GET",
                f"/api/chat/sessions/{session_id}/messages",
                params={"after": user_id, "limit": 1},
            )
            if history.status_code != 200:
                raise BenchmarkError(f"history read failed ({history.status_code})")
            message = next(
                (row for row in history.json() if row["id"] == assistant_id),
                None,
            )
            if message and message["status"] != "RUNNING":
                return self._reply(message, time.monotonic() - started)
            if time.monotonic() > deadline:
                raise BenchmarkError(f"reply {assistant_id} did not finish in time")
            time.sleep(2)

    @staticmethod
    def _reply(message: dict[str, Any], seconds: float) -> Reply:
        sources = message.get("sources") or []
        activity = message.get("activity") or {}
        return Reply(
            message_id=message["id"],
            status=message["status"],
            content=message.get("content", ""),
            # A citation names the document it came from; the same document cited twice counts once.
            document_ids=sorted(
                {source["documentId"] for source in sources if source.get("documentId")}
            ),
            steps=len(activity.get("steps", [])),
            seconds=seconds,
        )
