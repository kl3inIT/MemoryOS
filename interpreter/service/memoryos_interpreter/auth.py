"""MemoryOS addition: a shared API key for every ``/v1`` route (see NOTICE.md).

``/health`` stays open for container health checks. With neither ``API_KEY_FILE`` nor ``API_KEY``
set the service refuses to start, so no deployment can serve ``/v1`` unauthenticated by accident.
``ALLOW_UNAUTHENTICATED=true`` opts out for local runs and the CI e2e container, which is how
upstream behaves everywhere.
"""

from __future__ import annotations

import hmac
import os
from functools import lru_cache
from pathlib import Path
from typing import Annotated

from fastapi import Header, HTTPException, status

API_KEY_HEADER = "X-Api-Key"


@lru_cache(maxsize=1)
def configured_api_key() -> str | None:
    """Return the configured key; ``API_KEY_FILE`` wins over ``API_KEY``."""
    path = os.environ.get("API_KEY_FILE")
    if path:
        key = Path(path).read_text(encoding="utf-8").strip()
        if not key:
            raise ValueError("API_KEY_FILE is empty")
        return key
    return os.environ.get("API_KEY") or None


def unauthenticated_allowed() -> bool:
    """Whether running without a key was opted into explicitly."""
    return os.environ.get("ALLOW_UNAUTHENTICATED", "").strip().lower() == "true"


def require_api_key(
    x_api_key: Annotated[str | None, Header(alias=API_KEY_HEADER)] = None,
) -> None:
    """Reject a request whose ``X-Api-Key`` does not match the configured key."""
    expected = configured_api_key()
    if expected is None:
        return
    if x_api_key is None or not hmac.compare_digest(x_api_key.encode(), expected.encode()):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing API key",
            headers={"WWW-Authenticate": "ApiKey"},
        )
