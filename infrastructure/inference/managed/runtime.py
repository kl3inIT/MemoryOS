"""Manifest, filesystem and deadline boundaries shared by managed operator commands."""

from contextlib import contextmanager
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import signal
import stat


class RuntimeFailure(Exception):
    """A bounded, operator-safe failure code; never include remote payloads."""


ASSET_NAMES = frozenset({
    "config.json", "generation_config.json", "tokenizer.json",
    "tokenizer_config.json", "special_tokens_map.json", "model.safetensors",
})
TEMPLATE_NAME = "chat-template.jinja"
MAX_MANIFEST_BYTES = 65536


def safe_path(path):
    """Reject symlinks in every existing component, including the final entry."""
    path = Path(os.path.abspath(path))
    for part in (*reversed(path.parents), path):
        if part.is_symlink():
            raise RuntimeFailure("SYMLINK_PATH_REJECTED")
    return path


def regular_file(path, maximum):
    path = safe_path(path)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    with os.fdopen(os.open(path, flags), "rb") as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > maximum:
            raise RuntimeFailure("INVALID_FILE")
        data = source.read(maximum + 1)
        if len(data) > maximum:
            raise RuntimeFailure("FILE_TOO_LARGE")
        return data


def load_manifest(path):
    data = regular_file(path, MAX_MANIFEST_BYTES)
    try:
        manifest = json.loads(data)
        model = manifest["model"]
        files = model["files"]
        revision = model["revision"]
        if manifest["manifestVersion"] != 1 or revision != "12fd25f77366fa6b3b4b768ec3050bf629380bac":
            raise ValueError()
        if model["repository"] != "HuggingFaceTB/SmolLM2-135M-Instruct":
            raise ValueError()
        if model["assetBaseUrl"] != f"https://huggingface.co/{model['repository']}/resolve/{revision}/":
            raise ValueError()
        if len(files) != len(ASSET_NAMES) or {item["path"] for item in files} != ASSET_NAMES:
            raise ValueError()
        for item in [*files, model["chatTemplate"]]:
            if type(item["sizeBytes"]) is not int or not 0 < item["sizeBytes"] <= 512 * 1024 * 1024:
                raise ValueError()
            if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
                raise ValueError()
        total = sum(item["sizeBytes"] for item in files) + model["chatTemplate"]["sizeBytes"]
        if total != model["totalAssetBytesIncludingTemplate"] or total > 512 * 1024 * 1024:
            raise ValueError()
        if model["chatTemplate"]["sizeBytes"] > 16384:
            raise ValueError()
        if model["servedModelName"] != model["repository"] or model["dtype"] != "bfloat16" or model["quantization"] is not None:
            raise ValueError()
        if model["tokenizerProfile"] != "smollm2-135m-12fd25f-v1":
            raise ValueError()
        runtime = manifest["provisionalRuntime"]
        expected = {"contextTokens": 1024, "maxOutputTokens": 128, "maxRequestBodyBytes": 16384,
                    "maxBatchedTokens": 1024, "maxSequences": 1, "generationSlots": 1,
                    "queueCapacity": 0, "queueWaitMilliseconds": 0, "excessStatus": 429}
        if any(runtime[key] != value for key, value in expected.items()):
            raise ValueError()
        if runtime["serverGenerationOverride"] != {"max_new_tokens": 128}:
            raise ValueError()
        expected_environment = {
            "OMP_NUM_THREADS": "1", "MKL_NUM_THREADS": "1",
            "VLLM_CPU_OMP_THREADS_BIND": "nobind", "VLLM_CPU_KVCACHE_SPACE": "1",
            "VLLM_MAX_N_SEQUENCES": "1", "HF_HUB_OFFLINE": "1",
            "HF_HUB_DISABLE_TELEMETRY": "1", "VLLM_NO_USAGE_STATS": "1", "DO_NOT_TRACK": "1",
        }
        if runtime["environment"] != expected_environment:
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise RuntimeFailure("INVALID_SERVING_MANIFEST") from None
    return manifest


def verify_file(path, item, readonly=True):
    path = safe_path(path)
    digest = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    with os.fdopen(os.open(path, flags), "rb") as source:
        metadata = os.fstat(source.fileno())
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1
                or metadata.st_size != item["sizeBytes"]):
            raise RuntimeFailure("ASSET_SIZE_OR_TYPE_MISMATCH")
        if readonly and os.name == "posix" and metadata.st_mode & 0o222:
            raise RuntimeFailure("ASSET_NOT_READONLY")
        remaining = item["sizeBytes"]
        while remaining:
            chunk = source.read(min(1024 * 1024, remaining))
            if not chunk:
                raise RuntimeFailure("ASSET_SIZE_MISMATCH")
            remaining -= len(chunk)
            digest.update(chunk)
        if source.read(1) or digest.hexdigest() != item["sha256"]:
            raise RuntimeFailure("ASSET_CHECKSUM_MISMATCH")


def verify_assets(manifest, assets_root):
    revision_dir = safe_path(Path(assets_root) / manifest["model"]["revision"])
    metadata = revision_dir.stat()
    if not stat.S_ISDIR(metadata.st_mode):
        raise RuntimeFailure("ASSET_DIRECTORY_INVALID")
    if os.name == "posix" and metadata.st_mode & 0o222:
        raise RuntimeFailure("ASSET_DIRECTORY_NOT_READONLY")
    if {entry.name for entry in revision_dir.iterdir()} != ASSET_NAMES | {TEMPLATE_NAME}:
        raise RuntimeFailure("ASSET_ALLOWLIST_MISMATCH")
    for item in manifest["model"]["files"]:
        verify_file(revision_dir / item["path"], item)
    verify_file(revision_dir / TEMPLATE_NAME, manifest["model"]["chatTemplate"])
    return revision_dir


def read_key(path):
    path = safe_path(path)
    metadata = path.stat()
    if os.name == "posix" and (stat.S_IMODE(metadata.st_mode) != 0o400
                              or metadata.st_uid != 1654 or metadata.st_gid != 1654):
        raise RuntimeFailure("KEY_OWNER_OR_MODE_INVALID")
    data = regular_file(path, 65)
    if re.fullmatch(rb"[0-9a-f]{64}\n?", data) is None:
        raise RuntimeFailure("KEY_FORMAT_INVALID")
    return data.rstrip(b"\n").decode("ascii")


@contextmanager
def deadline(seconds):
    """Hard Linux wall-time bound, including resolver and slow-drip IO."""
    if not hasattr(signal, "SIGALRM"):
        raise RuntimeFailure("BOUNDED_NETWORK_COMMAND_REQUIRES_POSIX")

    def expired(signum, frame):
        raise RuntimeFailure("DEADLINE_EXCEEDED")

    previous = signal.signal(signal.SIGALRM, expired)
    previous_timer = signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, *previous_timer)
        signal.signal(signal.SIGALRM, previous)


class SafeDiagnosticFormatter(logging.Formatter):
    """Native errors may contain prompts/credentials; retain only code locations."""

    def format(self, record):
        return json.dumps({"event": "inference_diagnostic", "level": record.levelname,
                           "logger": record.name[:160], "line": record.lineno}, separators=(",", ":"))
