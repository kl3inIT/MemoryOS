import multiprocessing
import threading
from pathlib import Path

from memoryos_bench.auth import TokenStore


def test_concurrent_saves_keep_every_roles_latest_token(tmp_path: Path) -> None:
    store = TokenStore(tmp_path / "tokens.json")
    roles = [f"role{i}" for i in range(8)]

    def renew(role: str) -> None:
        for n in range(20):
            with store.locked():
                store.save_role(role, "https://issuer", f"{role}-{n}")

    threads = [threading.Thread(target=renew, args=(r,)) for r in roles]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    saved = store.load()
    assert {r: saved[r]["refreshToken"] for r in roles} == {r: f"{r}-19" for r in roles}
    assert not list(tmp_path.glob("*.tmp"))


def _renew_in_process(path: str, role: str) -> None:
    store = TokenStore(Path(path))
    for n in range(20):
        with store.locked():
            store.save_role(role, "https://issuer", f"{role}-{n}")


def test_processes_sharing_the_store_keep_every_roles_latest_token(tmp_path: Path) -> None:
    path = tmp_path / "tokens.json"
    processes = [
        multiprocessing.Process(target=_renew_in_process, args=(str(path), f"role{i}"))
        for i in range(4)
    ]
    for process in processes:
        process.start()
    for process in processes:
        process.join(60)

    saved = TokenStore(path).load()
    assert {r: saved[r]["refreshToken"] for r in saved} == {
        f"role{i}": f"role{i}-19" for i in range(4)
    }
