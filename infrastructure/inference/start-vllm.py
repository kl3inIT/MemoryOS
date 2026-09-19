import logging
from pathlib import Path
import re
import sys

from vllm.entrypoints.cli.main import main


def _redact(value, secret: str):
    if isinstance(value, str):
        return value.replace(secret, "<redacted>")
    if isinstance(value, dict):
        return {key: _redact(item, secret) for key, item in value.items()}
    if isinstance(value, tuple):
        return tuple(_redact(item, secret) for item in value)
    if isinstance(value, list):
        return [_redact(item, secret) for item in value]
    return value


class _SecretRedactionFilter(logging.Filter):
    def __init__(self, secret: str):
        super().__init__()
        self._secret = secret

    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = _redact(record.msg, self._secret)
        record.args = _redact(record.args, self._secret)
        return True


def run() -> int:
    secret_path = Path("/run/secrets/inference_api_key")
    try:
        api_key = secret_path.read_text(encoding="ascii").strip()
    except OSError as error:
        raise SystemExit("vLLM API key file is missing or unreadable.") from error

    if re.fullmatch(r"[0-9a-f]{64}", api_key) is None:
        raise SystemExit(
            "vLLM API key must contain exactly 64 hexadecimal characters."
        )

    redaction_filter = _SecretRedactionFilter(api_key)
    for handler in logging.getLogger("vllm").handlers:
        handler.addFilter(redaction_filter)

    # vLLM 0.28.0 exposes --api-key but no matching environment or file option.
    # Inject it only into the in-process parser so Docker metadata and the OS
    # process command line contain the file path, never the credential value.
    sys.argv = ["vllm", "serve", *sys.argv[1:], "--api-key", api_key]
    return main()


if __name__ == "__main__":
    raise SystemExit(run())
