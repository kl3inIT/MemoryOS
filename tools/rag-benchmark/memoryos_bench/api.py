"""A small MemoryOS API client over urllib."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

from memoryos_bench.auth import RoleSession


class ApiError(RuntimeError):
    def __init__(self, method: str, path: str, status: int, body: str) -> None:
        super().__init__(f"{method} {path} answered {status}: {body[:400]}")
        self.status = status


class Api:
    def __init__(self, origin: str, session: RoleSession) -> None:
        self._origin = origin.rstrip("/")
        self._session = session

    def get(self, path: str, **query: object) -> object:
        return self._call("GET", path, query=query)

    def post(self, path: str, body: object | None = None) -> object:
        return self._call("POST", path, body=body)

    def _call(
        self,
        method: str,
        path: str,
        body: object | None = None,
        query: dict | None = None,
        retried: bool = False,
    ) -> object:
        url = self._origin + path
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        # The bearer filter chain disables CSRF; the header is what the browser sends.
        headers = {
            "Authorization": "Bearer " + self._session.bearer(),
            "X-MemoryOS-CSRF": "1",
            "Accept": "application/json",
        }
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            if error.code == 401 and not retried:
                self._session.invalidate()
                return self._call(method, path, body, query, retried=True)
            raise ApiError(
                method, path, error.code, error.read().decode(errors="replace")
            ) from error


def put_object(url: str, headers: dict, data: bytes) -> None:
    """Sends one file to a presigned upload URL with exactly the headers the server signed."""
    request = urllib.request.Request(url, data=data, headers=dict(headers), method="PUT")
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            response.read()
    except urllib.error.HTTPError as error:
        raise ApiError(
            "PUT",
            urllib.parse.urlparse(url).path,
            error.code,
            error.read().decode(errors="replace"),
        ) from error
