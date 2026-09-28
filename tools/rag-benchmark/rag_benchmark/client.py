"""
MemoryOS HTTP access for one actor: a bearer token, the search page and a Chat turn read from saved
history.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field, replace
from typing import Any

import httpx

from .auth import AuthError, ClientCredentials, RefreshToken, StaticToken, TokenSource, TokenStore
from .config import Actor, Config


class BenchmarkError(RuntimeError):
    """The run cannot continue: authentication, authorization or a reply that never finished."""


@dataclass
class Reply:
    """One finished assistant message, as history saved it."""

    message_id: str
    status: str
    content: str
    # Documents the reply cited.
    document_ids: list[str]
    # Documents the Chat timeline showed as read while answering, cited or not.
    read_document_ids: list[str]
    # One entry per tool step: its queries, its effective filters and what it read. An empty
    # answer is diagnosed from the filters, which a bare step count cannot explain.
    timeline: list[dict[str, Any]]
    steps: int
    # From sending the question to reading the finished reply. History is polled, not streamed, so
    # the time to the first text is not observable here.
    seconds: float
    # The citation numbers the reply's sources hold; an inline `[n]` is valid only if n is here.
    citation_ids: list[int] = field(default_factory=list)
    # Why the server declined instead of answering (`no_evidence`, `uncited`, `blocked_topic`);
    # null for an answer, and absent on a server older than grounded mode.
    refusal_reason: str | None = None
    # The model configuration that produced this reply, and why the requested one was not used.
    model: str | None = None
    fallback: str | None = None


class ActorClient:
    """Every request carries one actor's token; nothing here shares state between actors."""

    def __init__(self, config: Config, actor: Actor) -> None:
        self._config = config
        self._actor = actor
        self._http = httpx.Client(base_url=config.base_url, timeout=config.timeout_seconds)
        self._auth_http = httpx.Client(timeout=config.timeout_seconds)
        self._tokens = self._token_source()
        # Fail at start rather than at the first question.
        try:
            self._tokens.bearer()
        except AuthError as failure:
            self.close()
            raise BenchmarkError(str(failure)) from failure

    @property
    def label(self) -> str:
        return self._actor.label

    def close(self) -> None:
        self._http.close()
        self._auth_http.close()

    def __enter__(self) -> ActorClient:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def _token_source(self) -> TokenSource:
        token_url = f"{self._config.issuer}/protocol/openid-connect/token"
        store = TokenStore(self._config.token_store)
        if store.refresh_token(self._actor.label):
            return RefreshToken(
                self._auth_http,
                token_url,
                self._config.login_client_id,
                store,
                self._actor.label,
            )
        if self._actor.client_id and self._actor.client_secret:
            return ClientCredentials(
                self._auth_http,
                token_url,
                self._actor.client_id,
                self._actor.client_secret,
                self._actor.label,
            )
        if self._actor.token:
            return StaticToken(self._actor.token)
        raise BenchmarkError(
            f"{self._actor.label}: no credential; run `rag-benchmark login --actor "
            f"{self._actor.label}`"
        )

    def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:  # noqa: ANN401
        # The bearer filter chain disables CSRF; the header is what the browser sends and costs
        # nothing here.
        extra = kwargs.pop("headers", {})
        response = self._send(method, path, extra, kwargs)
        if response.status_code == 401:
            # The token lapsed between renewal and use; renew once, then give up.
            self._tokens.invalidate()
            response = self._send(method, path, extra, kwargs)
        if response.status_code in (401, 403):
            raise BenchmarkError(f"{self._actor.label}: {path} refused ({response.status_code})")
        return response

    def _send(
        self, method: str, path: str, extra: dict[str, str], kwargs: dict[str, Any]
    ) -> httpx.Response:
        try:
            bearer = self._tokens.bearer()
        except AuthError as failure:
            raise BenchmarkError(str(failure)) from failure
        headers = {"Authorization": f"Bearer {bearer}", "X-MemoryOS-CSRF": "1", **extra}
        return self._http.request(method, path, headers=headers, **kwargs)

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

    def readable(self, document_id: str, generation: str) -> bool:
        """
        Whether this actor may read a document, as the server decides it: the Chat citation reader
        applies the same eligibility and generation checks as retrieval and needs only membership.
        """
        response = self._request(
            "GET",
            f"/api/chat/documents/{document_id}",
            params={"generation": generation, "from": 0},
        )
        if response.status_code == 200:
            return True
        if response.status_code == 404:
            return False
        raise BenchmarkError(f"document read failed ({response.status_code}) for {document_id}")

    # Chat ----------------------------------------------------------------------

    def ask(self, question: str) -> Reply:
        """One question in its own session, read from saved history so no event stream is parsed."""
        # The API requires a non-blank title; the session is deleted as soon as the reply is read.
        session = self._request("POST", "/api/chat/sessions", json={"title": "rag-benchmark"})
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
            json={
                "parentMessageId": parent_message_id,
                # Request identity: a fresh one per ask, so no two asks are deduplicated into one.
                "clientRequestId": str(uuid.uuid4()),
                "text": question,
                **(
                    {"modelConfigurationId": self._config.model_configuration_id}
                    if self._config.model_configuration_id
                    else {}
                ),
            },
        )
        if accepted.status_code != 202:
            raise BenchmarkError(f"message rejected ({accepted.status_code})")
        body = accepted.json()
        assistant_id, user_id = body["assistantMessageId"], body["userMessageId"]
        # What answered, as the API resolved it: a requested model can fall back, and a comparison
        # that cannot say which model produced a number is not a comparison.
        answered_by = body.get("modelConfigurationId")
        fallback = body.get("fallbackReason")
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
                reply = self._reply(message, time.monotonic() - started)
                return replace(reply, model=answered_by, fallback=fallback)
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
            read_document_ids=sorted(
                {
                    str(document["documentId"])
                    for step in activity.get("steps") or []
                    for document in step.get("documents") or []
                    if document.get("documentId")
                }
            ),
            timeline=[
                {
                    "position": step.get("position"),
                    "tool": step.get("toolName"),
                    "status": step.get("status"),
                    "failure": step.get("failure"),
                    "durationMs": step.get("durationMs"),
                    "queries": step.get("queries") or [],
                    "filters": step.get("filters"),
                    "documents": [
                        {
                            "documentId": document.get("documentId"),
                            "title": document.get("title"),
                            "from": document.get("startOrdinal"),
                            "to": document.get("endOrdinal"),
                        }
                        for document in step.get("documents") or []
                    ],
                }
                for step in activity.get("steps") or []
            ],
            steps=len(activity.get("steps", [])),
            seconds=seconds,
            citation_ids=sorted(
                {
                    int(source["citationId"])
                    for source in sources
                    if isinstance(source.get("citationId"), int)
                }
            ),
            refusal_reason=message.get("refusalReason"),
        )
