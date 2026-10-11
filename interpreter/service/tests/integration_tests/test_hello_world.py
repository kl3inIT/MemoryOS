from fastapi.testclient import TestClient

from memoryos_interpreter.main import create_app


def test_execute_returns_expected_payload() -> None:
    client = TestClient(create_app())

    response = client.post(
        "/v1/execute",
        json={
            "code": "print('hello')",
            "stdin": None,
            "timeout_ms": 1000,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["stdout"] == "hello\n"
    assert payload["stderr"] == ""
    assert payload["exit_code"] == 0
    assert payload["timed_out"] is False
    assert isinstance(payload["duration_ms"], int)
    assert payload["duration_ms"] >= 0


def test_a_run_that_draws_nothing_does_not_load_matplotlib() -> None:
    """The executor's start-up file loads the chart capture only when the code imports pyplot.

    Importing matplotlib on every start cost about half a second of each run, which is what left the one-second
    budget above without room on a busy runner.
    """
    client = TestClient(create_app())

    response = client.post(
        "/v1/execute",
        json={
            "code": "import sys\nprint('matplotlib' in sys.modules)",
            "stdin": None,
            "timeout_ms": 1000,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["stdout"] == "False\n"
    assert payload["exit_code"] == 0
