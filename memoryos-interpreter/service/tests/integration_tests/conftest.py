"""MemoryOS addition: the service refuses to start without an API key (see NOTICE.md).

Tests that are not about authentication run with the explicit opt-out, which is how a local run and
the CI e2e container are started. ``test_api_key.py`` clears it to exercise the closed path.
"""

from __future__ import annotations

from collections.abc import Iterator

import pytest


@pytest.fixture(autouse=True)
def _allow_unauthenticated(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    monkeypatch.setenv("ALLOW_UNAUTHENTICATED", "true")
    yield
