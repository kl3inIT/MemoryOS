#!/usr/bin/env python3
"""Bounded manifest identity, authenticated generation and engine-idle probes."""

import argparse
import hashlib
import http.client
import json
import math
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from runtime import RuntimeFailure, deadline, load_manifest, read_key, regular_file


MAX_RESPONSE = 1024 * 1024
METRICS = ("vllm:num_requests_running", "vllm:num_requests_waiting", "vllm:generation_tokens_total")


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeFailure("PROBE_REDIRECT_REJECTED")


class Client:
    def __init__(self, base_url, key, timeout):
        parsed = urllib.parse.urlsplit(base_url)
        if (parsed.scheme not in ("http", "https") or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment or parsed.path not in ("", "/", "/v1", "/v1/")):
            raise RuntimeFailure("PROBE_BASE_URL_INVALID")
        self.base_url = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))
        self.key = key
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())

    def open(self, path, body=None):
        headers = {"Authorization": "Bearer " + self.key, "Accept-Encoding": "identity"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            headers["Accept"] = "text/event-stream"
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, headers=headers, data=data)
        response = self.opener.open(request, timeout=min(self.timeout, 120))
        if response.status != 200:
            response.close()
            raise RuntimeFailure("PROBE_HTTP_STATUS")
        return response

    def get(self, path):
        with self.open(path) as response:
            data = response.read(MAX_RESPONSE + 1)
            if len(data) > MAX_RESPONSE:
                raise RuntimeFailure("PROBE_RESPONSE_TOO_LARGE")
            return data


def ready(client, manifest):
    result = json.loads(client.get("/v1/models"))
    models = result.get("data")
    expected_root = "/var/lib/memoryos-inference/assets/" + manifest["model"]["revision"]
    if (result.get("object") != "list" or not isinstance(models, list) or len(models) != 1
            or models[0].get("id") != manifest["model"]["servedModelName"]
            or models[0].get("root") != expected_root
            or models[0].get("max_model_len") != manifest["provisionalRuntime"]["contextTokens"]):
        raise RuntimeFailure("SERVED_MODEL_IDENTITY_MISMATCH")


def generation(client, manifest, manifest_hash):
    ready(client, manifest)
    body = {"model": manifest["model"]["servedModelName"],
            "messages": [{"role": "user", "content": "Say hello in one short sentence."}],
            "max_tokens": 16, "n": 1, "temperature": 0, "stream": True,
            "stream_options": {"include_usage": True}}
    started = time.monotonic()
    first_content = None
    finish = None
    usage = None
    total = 0
    done = False
    with client.open("/v1/chat/completions", body) as response:
        if response.headers.get_content_type() != "text/event-stream":
            raise RuntimeFailure("GENERATION_NOT_SSE")
        while True:
            line = response.readline(16385)
            total += len(line)
            if len(line) > 16384 or total > MAX_RESPONSE:
                raise RuntimeFailure("GENERATION_RESPONSE_TOO_LARGE")
            if not line:
                break
            line = line.strip()
            if not line or line.startswith(b":"):
                continue
            if not line.startswith(b"data: "):
                raise RuntimeFailure("GENERATION_SSE_INVALID")
            payload = line[6:]
            if payload == b"[DONE]":
                done = True
                break
            event = json.loads(payload)
            if (event.get("error") is not None
                    or event.get("model") != manifest["model"]["servedModelName"]
                    or event.get("system_fingerprint") not in (None, "memoryos-" + manifest_hash)):
                raise RuntimeFailure("GENERATION_IDENTITY_OR_ERROR")
            choices = event.get("choices")
            if not isinstance(choices, list) or len(choices) > 1:
                raise RuntimeFailure("GENERATION_FANOUT_INVALID")
            for choice in choices:
                if choice.get("index") != 0:
                    raise RuntimeFailure("GENERATION_FANOUT_INVALID")
                content = choice.get("delta", {}).get("content")
                if content:
                    if not isinstance(content, str) or finish is not None:
                        raise RuntimeFailure("GENERATION_CONTENT_INVALID")
                    if first_content is None:
                        first_content = time.monotonic() - started
                reason = choice.get("finish_reason")
                if reason is not None:
                    if finish is not None or reason not in ("stop", "length"):
                        raise RuntimeFailure("GENERATION_FINISH_INVALID")
                    finish = reason
            if event.get("usage") is not None:
                if event.get("system_fingerprint") != "memoryos-" + manifest_hash:
                    raise RuntimeFailure("GENERATION_IDENTITY_OR_ERROR")
                usage = event["usage"]
    if not done or first_content is None or finish is None or not isinstance(usage, dict):
        raise RuntimeFailure("GENERATION_INCOMPLETE")
    prompt = usage.get("prompt_tokens")
    completion = usage.get("completion_tokens")
    if (type(prompt) is not int or type(completion) is not int or prompt <= 0
            or not 0 < completion <= 16 or prompt + completion > 1024
            or usage.get("total_tokens") != prompt + completion):
        raise RuntimeFailure("GENERATION_USAGE_INVALID")
    return {"firstContentSeconds": round(first_content, 4),
            "elapsedSeconds": round(time.monotonic() - started, 4),
            "completionTokens": completion, "finishReason": finish}


def metric_values(data, model_name):
    values = {}
    sample = re.compile(r'^(vllm:[a-z_]+)\{([^}]*)\}\s+([^\s]+)(?:\s+\S+)?$')
    labels = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"(?:,|$)')
    for line in data.decode("utf-8").splitlines():
        matched = sample.fullmatch(line)
        if not matched or matched[1] not in METRICS:
            continue
        parsed = {key: json.loads('"' + value + '"') for key, value in labels.findall(matched[2])}
        if parsed.get("model_name") != model_name:
            raise RuntimeFailure("METRIC_MODEL_IDENTITY_MISMATCH")
        if parsed.get("engine") != "0" or matched[1] in values:
            raise RuntimeFailure("METRIC_ENGINE_IDENTITY_MISMATCH")
        value = float(matched[3])
        if not math.isfinite(value) or value < 0:
            raise RuntimeFailure("METRIC_VALUE_INVALID")
        values[matched[1]] = value
    if set(values) != set(METRICS):
        raise RuntimeFailure("REQUIRED_ENGINE_METRICS_MISSING")
    return values


def idle(client, manifest):
    ready(client, manifest)
    client.get("/health")
    until = time.monotonic() + 8
    previous_counter = None
    while True:
        values = metric_values(client.get("/metrics"), manifest["model"]["servedModelName"])
        if values[METRICS[0]] != 0 or values[METRICS[1]] != 0:
            raise RuntimeFailure("ENGINE_BUSY")
        counter = values[METRICS[2]]
        if previous_counter is not None and counter != previous_counter:
            raise RuntimeFailure("ENGINE_GENERATION_NOT_QUIET")
        previous_counter = counter
        if time.monotonic() >= until:
            return {"quietObservationSeconds": 8}
        time.sleep(min(1, until - time.monotonic()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--key-file", required=True, type=Path)
    parser.add_argument("--mode", required=True, choices=("ready", "generation", "idle"))
    parser.add_argument("--timeout", type=float)
    args = parser.parse_args()
    timeout = args.timeout if args.timeout is not None else (120 if args.mode == "generation" else 15)
    try:
        if not math.isfinite(timeout) or not 0 < timeout <= 600:
            raise RuntimeFailure("PROBE_TIMEOUT_INVALID")
        manifest = load_manifest(args.manifest)
        manifest_hash = hashlib.sha256(regular_file(args.manifest, 65536)).hexdigest()
        client = Client(args.base_url, read_key(args.key_file), timeout)
        with deadline(timeout):
            if args.mode == "generation":
                details = generation(client, manifest, manifest_hash)
            elif args.mode == "idle":
                details = idle(client, manifest)
            else:
                ready(client, manifest)
                details = {}
        print(json.dumps({"result": "PASS", "mode": args.mode, "manifestSha256": manifest_hash, **details}))
        return 0
    except RuntimeFailure as error:
        print(f"Inference probe failed: {error}", file=sys.stderr)
    except urllib.error.HTTPError as error:
        print(f"Inference probe failed: HTTP_{error.code}", file=sys.stderr)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, http.client.HTTPException):
        print("Inference probe failed: TRANSPORT_OR_RESPONSE_ERROR", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
