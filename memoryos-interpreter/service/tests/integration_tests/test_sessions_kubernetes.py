"""Unit tests for KubernetesExecutor session methods.

Mocks the Kubernetes API so the session lifecycle can be exercised without
a real cluster.
"""

from __future__ import annotations

import time
from unittest.mock import MagicMock, patch

import pytest
from kubernetes.client import V1Pod
from kubernetes.client.exceptions import ApiException

from memoryos_interpreter.services.executor_base import (
    SESSION_APP_LABEL,
    SESSION_COMPONENT_LABEL,
    SESSION_EXPIRES_AT_KEY,
    SESSION_NAME_PREFIX,
)
from memoryos_interpreter.services.executor_kubernetes import (
    SESSION_LABEL_SELECTOR,
    KubernetesExecutor,
)


@pytest.fixture()
def v1() -> MagicMock:
    """The mocked CoreV1Api the executor fixture uses."""
    v1 = MagicMock()
    pod_mock = MagicMock()
    pod_mock.status.phase = "Running"
    v1.read_namespaced_pod.return_value = pod_mock
    return v1


@pytest.fixture()
def executor(v1: MagicMock) -> KubernetesExecutor:
    """Create a KubernetesExecutor bypassing __init__ (no cluster needed)."""
    inst = KubernetesExecutor.__new__(KubernetesExecutor)
    inst.v1 = v1
    inst.namespace = "test"
    inst.image = "test:latest"
    inst.service_account = ""
    inst.net_admin_lockdown = True
    inst.owner_reference = None
    return inst


def _created_pod(v1: MagicMock) -> V1Pod:
    pod = v1.create_namespaced_pod.call_args.kwargs["body"]
    assert isinstance(pod, V1Pod)
    return pod


def _make_pod(name: str, expires_at: float | None) -> MagicMock:
    pod = MagicMock()
    pod.metadata.name = name
    pod.metadata.annotations = (
        {SESSION_EXPIRES_AT_KEY: str(expires_at)} if expires_at is not None else {}
    )
    return pod


# ---------------------------------------------------------------------------
# create_session
# ---------------------------------------------------------------------------


def test_create_session_returns_session_info(executor: KubernetesExecutor) -> None:
    before = time.time()
    info = executor.create_session(ttl_seconds=600)

    assert info.session_id.startswith(SESSION_NAME_PREFIX)
    assert before + 600 <= info.expires_at <= time.time() + 600


def test_create_session_pod_carries_session_metadata(
    executor: KubernetesExecutor, v1: MagicMock
) -> None:
    info = executor.create_session(ttl_seconds=600)

    metadata = _created_pod(v1).metadata
    assert metadata is not None
    assert metadata.labels is not None
    assert metadata.annotations is not None
    assert metadata.name == info.session_id
    assert metadata.labels["app"] == SESSION_APP_LABEL
    assert metadata.labels["component"] == SESSION_COMPONENT_LABEL
    assert metadata.annotations[SESSION_EXPIRES_AT_KEY] == str(info.expires_at)


def test_create_session_sets_active_deadline(executor: KubernetesExecutor, v1: MagicMock) -> None:
    """active_deadline_seconds is what makes kubelet stop the pod at TTL even if API is down."""
    executor.create_session(ttl_seconds=600)

    spec = _created_pod(v1).spec
    assert spec is not None
    assert spec.active_deadline_seconds == 600
    assert spec.containers[0].command == ["sleep", "600"]


def test_create_session_mounts_no_service_account_token(
    executor: KubernetesExecutor,
    v1: MagicMock,
) -> None:
    """Untrusted code in an executor pod never holds a Kubernetes API credential."""
    executor.service_account = "executor"
    executor.create_session(ttl_seconds=600)

    spec = _created_pod(v1).spec
    assert spec is not None
    assert spec.service_account_name == "executor"
    assert spec.automount_service_account_token is False


def test_create_session_adds_net_admin_lockdown_init_container(
    executor: KubernetesExecutor,
    v1: MagicMock,
) -> None:
    """With lockdown enabled, a NET_ADMIN init container drops outbound traffic."""
    executor.create_session(ttl_seconds=600)

    spec = _created_pod(v1).spec
    assert spec is not None
    init_containers = spec.init_containers
    assert init_containers is not None
    assert len(init_containers) == 1
    lockdown = init_containers[0]
    assert lockdown.name == "network-lockdown"
    assert lockdown.security_context is not None
    assert lockdown.security_context.capabilities is not None
    assert lockdown.security_context.capabilities.add == ["NET_ADMIN"]


def test_create_session_omits_lockdown_when_disabled(
    executor: KubernetesExecutor,
    v1: MagicMock,
) -> None:
    """With lockdown disabled, no privileged init container is added (rely on NetworkPolicy)."""
    executor.net_admin_lockdown = False
    executor.create_session(ttl_seconds=600)

    spec = _created_pod(v1).spec
    assert spec is not None
    assert spec.init_containers is None


def test_create_session_stages_files(executor: KubernetesExecutor) -> None:
    with patch.object(executor, "_upload_tar_to_pod") as upload:
        info = executor.create_session(
            ttl_seconds=300,
            files=[("data.txt", b"hello")],
        )

    upload.assert_called_once()
    pod_name_arg, tar_arg = upload.call_args.args
    assert pod_name_arg == info.session_id
    assert isinstance(tar_arg, bytes)
    assert len(tar_arg) > 0


def test_create_session_skips_upload_when_no_files(executor: KubernetesExecutor) -> None:
    with patch.object(executor, "_upload_tar_to_pod") as upload:
        executor.create_session(ttl_seconds=300)

    upload.assert_not_called()


def test_create_session_cleans_up_on_staging_failure(executor: KubernetesExecutor) -> None:
    with (
        patch.object(executor, "_upload_tar_to_pod", side_effect=RuntimeError("boom")),
        patch.object(executor, "_cleanup_pod") as cleanup,
        pytest.raises(RuntimeError, match="boom"),
    ):
        executor.create_session(ttl_seconds=300, files=[("data.txt", b"x")])

    cleanup.assert_called_once()


# ---------------------------------------------------------------------------
# delete_session
# ---------------------------------------------------------------------------


def test_delete_session_returns_true_on_success(
    executor: KubernetesExecutor, v1: MagicMock
) -> None:
    assert executor.delete_session(f"{SESSION_NAME_PREFIX}abc") is True
    v1.delete_namespaced_pod.assert_called_once()


def test_delete_session_returns_false_on_404(executor: KubernetesExecutor, v1: MagicMock) -> None:
    v1.delete_namespaced_pod.side_effect = ApiException(status=404)
    assert executor.delete_session(f"{SESSION_NAME_PREFIX}abc") is False


def test_delete_session_rejects_non_session_id(executor: KubernetesExecutor, v1: MagicMock) -> None:
    """Prefix check prevents accidentally deleting unrelated pods."""
    assert executor.delete_session("code-exec-abc") is False
    v1.delete_namespaced_pod.assert_not_called()


def test_delete_session_propagates_other_api_errors(
    executor: KubernetesExecutor, v1: MagicMock
) -> None:
    v1.delete_namespaced_pod.side_effect = ApiException(status=500)
    with pytest.raises(ApiException):
        executor.delete_session(f"{SESSION_NAME_PREFIX}abc")


# ---------------------------------------------------------------------------
# reap_expired_sessions
# ---------------------------------------------------------------------------


def test_reap_deletes_expired_pods(executor: KubernetesExecutor, v1: MagicMock) -> None:
    now = time.time()
    expired_pod = _make_pod("code-session-old", expires_at=now - 100)
    fresh_pod = _make_pod("code-session-new", expires_at=now + 100)
    v1.list_namespaced_pod.return_value = MagicMock(items=[expired_pod, fresh_pod])

    reaped = executor.reap_expired_sessions()

    assert reaped == 1
    v1.list_namespaced_pod.assert_called_once_with(
        namespace=executor.namespace,
        label_selector=SESSION_LABEL_SELECTOR,
    )
    v1.delete_namespaced_pod.assert_called_once()
    delete_kwargs = v1.delete_namespaced_pod.call_args.kwargs
    assert delete_kwargs["name"] == "code-session-old"


def test_reap_skips_pods_without_annotation(executor: KubernetesExecutor, v1: MagicMock) -> None:
    pod = _make_pod("code-session-bare", expires_at=None)
    v1.list_namespaced_pod.return_value = MagicMock(items=[pod])

    assert executor.reap_expired_sessions() == 0
    v1.delete_namespaced_pod.assert_not_called()


def test_reap_skips_pods_with_invalid_annotation(
    executor: KubernetesExecutor, v1: MagicMock
) -> None:
    pod = MagicMock()
    pod.metadata.name = "code-session-bad"
    pod.metadata.annotations = {SESSION_EXPIRES_AT_KEY: "not-a-number"}
    v1.list_namespaced_pod.return_value = MagicMock(items=[pod])

    assert executor.reap_expired_sessions() == 0
    v1.delete_namespaced_pod.assert_not_called()


def test_reap_handles_list_failure(executor: KubernetesExecutor, v1: MagicMock) -> None:
    v1.list_namespaced_pod.side_effect = ApiException(status=500)
    assert executor.reap_expired_sessions() == 0


def test_reap_continues_when_individual_delete_404s(
    executor: KubernetesExecutor, v1: MagicMock
) -> None:
    """Race: pod can vanish between list and delete — treat as already-gone."""
    now = time.time()
    expired_a = _make_pod("code-session-a", expires_at=now - 100)
    expired_b = _make_pod("code-session-b", expires_at=now - 100)
    v1.list_namespaced_pod.return_value = MagicMock(items=[expired_a, expired_b])
    v1.delete_namespaced_pod.side_effect = [ApiException(status=404), None]

    assert executor.reap_expired_sessions() == 1


def test_reap_keeps_going_when_delete_errors(executor: KubernetesExecutor, v1: MagicMock) -> None:
    """A 500 on one delete shouldn't prevent others from being reaped."""
    now = time.time()
    expired_a = _make_pod("code-session-a", expires_at=now - 100)
    expired_b = _make_pod("code-session-b", expires_at=now - 100)
    v1.list_namespaced_pod.return_value = MagicMock(items=[expired_a, expired_b])
    v1.delete_namespaced_pod.side_effect = [ApiException(status=500), None]

    assert executor.reap_expired_sessions() == 1
