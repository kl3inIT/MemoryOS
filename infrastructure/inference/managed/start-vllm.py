#!/usr/bin/env python3
"""Start only the verified local, pinned CPU model under the managed envelope."""

import hashlib
import json
import logging.config
import os
from pathlib import Path
import stat
import sys

from runtime import (RuntimeFailure, TEMPLATE_NAME, load_manifest, read_key,
                     regular_file, safe_path, verify_assets)


ROOT = Path("/opt/memoryos-inference")
ASSETS = Path("/var/lib/memoryos-inference/assets")
CACHE = Path("/var/cache/memoryos-inference")


def main():
    try:
        if os.getuid() != 1654 or os.getgid() != 1654:
            raise RuntimeFailure("ENGINE_IDENTITY_INVALID")
        os.umask(0o077)
        manifest = load_manifest(ROOT / "manifest.json")
        model_path = verify_assets(manifest, ASSETS)
        key = read_key("/run/secrets/inference_api_key")
        root = safe_path(CACHE)
        metadata = root.stat()
        if (not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != 1654
                or metadata.st_gid != 1654 or metadata.st_mode & 0o077):
            raise RuntimeFailure("CACHE_OWNER_OR_MODE_INVALID")
        for directory in ("home", "huggingface", "vllm", "torch", "inductor", "triton", "numba", "xdg"):
            path = safe_path(CACHE / directory)
            path.mkdir(mode=0o700, exist_ok=True)
            if not path.is_dir() or path.stat().st_uid != 1654 or path.stat().st_mode & 0o077:
                raise RuntimeFailure("CACHE_CHILD_OWNER_OR_MODE_INVALID")
        os.environ.update(manifest["provisionalRuntime"]["environment"])
        os.environ.update({
            "HOME": str(CACHE / "home"), "HF_HOME": str(CACHE / "huggingface"),
            "TRANSFORMERS_OFFLINE": "1", "CUDA_VISIBLE_DEVICES": "",
            "VLLM_CACHE_ROOT": str(CACHE / "vllm"), "TORCH_HOME": str(CACHE / "torch"),
            "TORCHINDUCTOR_CACHE_DIR": str(CACHE / "inductor"),
            "TRITON_CACHE_DIR": str(CACHE / "triton"), "NUMBA_CACHE_DIR": str(CACHE / "numba"),
            "XDG_CACHE_HOME": str(CACHE / "xdg"), "TMPDIR": "/tmp",
            "VLLM_LOGGING_CONFIG_PATH": str(ROOT / "logging.json"),
            "VLLM_CONFIGURE_LOGGING": "1", "PYTHONPATH": str(ROOT),
            "PYTHONDONTWRITEBYTECODE": "1", "TOKENIZERS_PARALLELISM": "false",
            # Set only after process creation: absent from Compose metadata and argv.
            "VLLM_API_KEY": key,
        })
        del key
        logging.config.dictConfig(json.loads(regular_file(ROOT / "logging.json", 65536)))
        manifest_hash = hashlib.sha256(regular_file(ROOT / "manifest.json", 65536)).hexdigest()
        runtime = manifest["provisionalRuntime"]
        sys.argv = [
            "vllm", "serve", str(model_path),
            f"--tokenizer={model_path}", "--tokenizer-mode=hf",
            f"--served-model-name={manifest['model']['servedModelName']}",
            f"--chat-template={model_path / TEMPLATE_NAME}",
            "--chat-template-content-format=string", "--dtype=bfloat16",
            f"--max-model-len={runtime['contextTokens']}",
            f"--max-num-seqs={runtime['maxSequences']}",
            f"--max-num-batched-tokens={runtime['maxBatchedTokens']}",
            "--generation-config=auto", '--override-generation-config={"max_new_tokens":128}',
            "--load-format=safetensors", "--host=0.0.0.0", "--port=8000",
            "--disable-uvicorn-access-log", "--disable-fastapi-docs", "--max-log-len=0",
            f"--log-config-file={ROOT / 'logging.json'}",
            "--fingerprint-mode=custom", f"--fingerprint-value=memoryos-{manifest_hash}",
        ]
        print(json.dumps({"event": "inference_start", "manifestSha256": manifest_hash,
                          "revision": manifest["model"]["revision"]}), flush=True)
        # Import only after local verification, offline settings and safe logging.
        from vllm.entrypoints.cli.main import main as serve
        return serve()
    except RuntimeFailure as error:
        print(f"Inference engine startup failed: {error}", file=sys.stderr)
    except Exception:
        # Provider/native exception text can include prompts or credentials.
        print("Inference engine failed: ENGINE_START_OR_RUNTIME_ERROR", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
