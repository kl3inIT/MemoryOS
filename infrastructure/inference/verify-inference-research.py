#!/usr/bin/env python3
"""Exercise the MEM-66 research gateway and write redacted evidence files."""

import argparse
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.parse import urlsplit

MODEL = "HuggingFaceTB/SmolLM2-135M-Instruct"


class VerificationFailure(RuntimeError):
    pass


def request(base_url: str, path: str, *, method="GET", key=None, body=None, timeout=30):
    parsed = urlsplit(base_url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    headers = {}
    if key is not None:
        headers["Authorization"] = f"Bearer {key}"
    encoded_body = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        encoded_body = body if isinstance(body, bytes) else json.dumps(body, separators=(",", ":")).encode()
    try:
        connection.request(method, path, body=encoded_body, headers=headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def run(command, *, environment=None, timeout=120, check=True):
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=timeout,
        env=environment,
    )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise VerificationFailure(f"Command failed ({result.returncode}): {detail}")
    return result


def metric_value(metrics: str, name: str, required_label="") -> float:
    for line in metrics.splitlines():
        if not line.startswith(name + "{"):
            continue
        if required_label and required_label not in line:
            continue
        return float(line.rsplit(None, 1)[1])
    raise VerificationFailure(f"Metric not found: {name} {required_label}")


def stream(base_url: str, key: str, body: dict) -> dict:
    parsed = urlsplit(base_url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=30)
    started = time.monotonic()
    events = 0
    content_events = 0
    first_content = None
    done = False
    try:
        connection.request(
            "POST",
            "/v1/chat/completions",
            body=json.dumps(body, separators=(",", ":")),
            headers={
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
            },
        )
        response = connection.getresponse()
        status = response.status
        raw_lines = []
        while True:
            raw = response.readline()
            if not raw:
                break
            raw_lines.append(raw)
            line = raw.decode().strip()
            if not line.startswith("data: "):
                continue
            events += 1
            event_text = line[6:]
            if event_text == "[DONE]":
                done = True
                break
            event = json.loads(event_text)
            content = event.get("choices", [{}])[0].get("delta", {}).get("content")
            if content:
                content_events += 1
                if first_content is None:
                    first_content = time.monotonic() - started
        return {
            "status": status,
            "events": events,
            "content_events": content_events,
            "done": done,
            "first_content_seconds": round(first_content, 3) if first_content is not None else None,
            "total_seconds": round(time.monotonic() - started, 3),
            "body": b"".join(raw_lines),
        }
    finally:
        connection.close()


def disconnect_stream(base_url: str, key: str, body: dict, seconds: float, outcome: dict):
    parsed = urlsplit(base_url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=30)
    started = time.monotonic()
    content_events = 0
    try:
        connection.request(
            "POST",
            "/v1/chat/completions",
            body=json.dumps(body, separators=(",", ":")),
            headers={
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
            },
        )
        response = connection.getresponse()
        outcome["status"] = response.status
        while time.monotonic() - started < seconds:
            raw = response.readline()
            if not raw:
                break
            line = raw.decode().strip()
            if line.startswith("data: ") and line[6:] != "[DONE]":
                event = json.loads(line[6:])
                if event.get("choices", [{}])[0].get("delta", {}).get("content"):
                    content_events += 1
        outcome["content_events_before_disconnect"] = content_events
        outcome["disconnect_seconds"] = round(time.monotonic() - started, 3)
    finally:
        connection.close()
        outcome["disconnected"] = True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-key-file", type=Path, required=True)
    parser.add_argument("--evidence-directory", type=Path, required=True)
    parser.add_argument("--gateway-url", default="http://127.0.0.1:18088")
    parser.add_argument("--compose-file", type=Path, default=Path("infrastructure/deployment/compose.inference-research.yaml"))
    parser.add_argument("--project-name", default="memoryos-inference-research")
    parser.add_argument("--restart-timeout-seconds", type=int, default=420)
    parser.add_argument("--confirm-restart", action="store_true")
    args = parser.parse_args()

    key = args.api_key_file.read_text(encoding="ascii").strip()
    if re.fullmatch(r"[0-9a-f]{64}", key) is None:
        raise VerificationFailure("API key must contain 64 hexadecimal characters")

    evidence = args.evidence_directory
    evidence.mkdir(parents=True, exist_ok=True)
    compose_environment = os.environ.copy()
    compose_environment["MEMORYOS_INFERENCE_API_KEY_FILE"] = str(args.api_key_file.resolve())
    compose_prefix = [
        "docker", "compose", "-p", args.project_name,
        "-f", str(args.compose_file),
    ]

    def compose(*arguments, timeout=120, check=True):
        return run(
            [*compose_prefix, *arguments],
            environment=compose_environment,
            timeout=timeout,
            check=check,
        )

    vllm_container = compose("ps", "-q", "vllm").stdout.strip()
    gateway_container = compose("ps", "-q", "gateway").stdout.strip()
    if not vllm_container or not gateway_container:
        raise VerificationFailure("The research Compose project is not running")

    def vllm_metrics() -> str:
        code = (
            "import urllib.request;"
            "print(urllib.request.urlopen('http://127.0.0.1:8000/metrics',"
            "timeout=3).read().decode(),end='')"
        )
        return run(["docker", "exec", vllm_container, "python3", "-c", code], timeout=15).stdout

    def record(name: str, body: bytes):
        (evidence / name).write_bytes(body)

    results = {}
    prompts = [
        "Explain a reverse proxy in one sentence.",
        "List several uses of a library.",
        "Repeat the word token separated by spaces until the response limit is reached.",
    ]

    status, body = request(args.gateway_url, "/v1/models")
    record("listing-missing-key.json", body)
    results["listing_missing_key"] = status
    status, body = request(args.gateway_url, "/v1/models", key="wrong-key")
    record("listing-wrong-key.json", body)
    results["listing_wrong_key"] = status
    status, body = request(args.gateway_url, "/v1/models", key=key)
    record("listing.json", body)
    results["listing"] = status
    results["model_present"] = MODEL in body.decode()

    for path, name in (("/metrics", "metrics_route"), ("/invocations", "invocations_route")):
        status, body = request(args.gateway_url, path, key=key)
        record(name + ".txt", body)
        results[name] = status

    valid = {
        "model": MODEL,
        "max_tokens": 24,
        "temperature": 0,
        "stream": False,
        "messages": [{"role": "user", "content": prompts[0]}],
    }
    status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", body=valid)
    record("chat-missing-key.json", body)
    results["chat_missing_key"] = status
    status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", key=key, body=valid)
    record("chat.json", body)
    chat = json.loads(body)
    results["chat"] = status
    results["chat_has_content"] = bool(chat.get("choices", [{}])[0].get("message", {}).get("content"))

    unsupported = {**valid, "model": "model-khong-ton-tai"}
    status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", key=key, body=unsupported)
    record("unsupported-model.json", body)
    results["unsupported_model"] = status
    status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", key=key, body=b'{"model":')
    record("malformed.json", body)
    results["malformed"] = status

    over_context = {
        "model": MODEL,
        "max_tokens": 1,
        "stream": False,
        "messages": [{"role": "user", "content": "token " * 1500}],
    }
    status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", key=key, body=over_context)
    record("over-context.json", body)
    results["over_context"] = status
    results["over_context_mentions_limit"] = "1024" in body.decode() or "context" in body.decode().lower()

    output_limit = {
        "model": MODEL,
        "max_tokens": 5,
        "temperature": 0,
        "stream": False,
        "messages": [{"role": "user", "content": "Write at least one hundred words about databases."}],
    }
    status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", key=key, body=output_limit)
    record("output-limit.json", body)
    limited = json.loads(body)
    results["output_limit"] = status
    results["output_tokens"] = limited.get("usage", {}).get("completion_tokens")
    results["output_within_limit"] = results["output_tokens"] <= 5

    oversized = {
        "model": MODEL,
        "max_tokens": 1,
        "stream": False,
        "messages": [{"role": "user", "content": "x" * 20000}],
    }
    status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", key=key, body=oversized)
    record("oversized.txt", body)
    results["oversized"] = status

    streamed = stream(
        args.gateway_url,
        key,
        {
            "model": MODEL,
            "max_tokens": 24,
            "temperature": 0,
            "stream": True,
            "messages": [{"role": "user", "content": prompts[1]}],
        },
    )
    record("stream.sse", streamed.pop("body"))
    results["stream"] = streamed

    metrics_before = vllm_metrics()
    baseline_generation = metric_value(metrics_before, "vllm:generation_tokens_total")
    cancellation = {}
    worker = threading.Thread(
        target=disconnect_stream,
        args=(
            args.gateway_url,
            key,
            {
                "model": MODEL,
                "max_tokens": 128,
                "temperature": 0,
                "stream": True,
                "ignore_eos": True,
                "messages": [{"role": "user", "content": prompts[2]}],
            },
            3.0,
            cancellation,
        ),
    )
    worker.start()
    maximum_running = 0.0
    while worker.is_alive():
        maximum_running = max(
            maximum_running,
            metric_value(vllm_metrics(), "vllm:num_requests_running"),
        )
        time.sleep(0.15)
    worker.join()
    time.sleep(2)
    metrics_after = vllm_metrics()
    generated_before_stop = metric_value(metrics_after, "vllm:generation_tokens_total") - baseline_generation
    running_after = metric_value(metrics_after, "vllm:num_requests_running")
    time.sleep(8)
    metrics_stable = vllm_metrics()
    generated_later = metric_value(metrics_stable, "vllm:generation_tokens_total") - baseline_generation - generated_before_stop
    running_later = metric_value(metrics_stable, "vllm:num_requests_running")
    cancellation.update({
        "maximum_running": maximum_running,
        "generated_before_stop": generated_before_stop,
        "generated_during_followup": generated_later,
        "running_after": running_after,
        "running_after_followup": running_later,
    })
    results["cancellation"] = cancellation
    record("vllm-metrics.txt", metrics_stable.encode())

    inspect_vllm = run(["docker", "inspect", vllm_container]).stdout
    inspect_gateway = run(["docker", "inspect", gateway_container]).stdout
    vllm_process_scan = """
from pathlib import Path

secret = Path("/run/secrets/inference_api_key").read_bytes().strip()
present = False
for command_line in Path("/proc").glob("[0-9]*/cmdline"):
    try:
        present = present or secret in command_line.read_bytes()
    except OSError:
        pass
print("present" if present else "absent")
"""
    process_vllm = run([
        "docker",
        "exec",
        vllm_container,
        "python3",
        "-c",
        vllm_process_scan,
    ]).stdout.strip()
    gateway_process_scan = (
        "secret=$(cat /run/secrets/inference_api_key); present=0; "
        "for command_line in /proc/[0-9]*/cmdline; do "
        "value=$(tr '\\000' ' ' <\"$command_line\" 2>/dev/null || true); "
        "case \"$value\" in *\"$secret\"*) present=1;; esac; "
        "done; printf '%s' \"$present\""
    )
    process_gateway = run([
        "docker",
        "exec",
        gateway_container,
        "sh",
        "-c",
        gateway_process_scan,
    ]).stdout.strip()
    vllm_data = json.loads(inspect_vllm)[0]
    results["isolation"] = {
        "vllm_has_no_published_port": not vllm_data["HostConfig"]["PortBindings"],
        "secret_absent_from_inspect": key not in inspect_vllm + inspect_gateway,
        "secret_absent_from_process_commands": (
            process_vllm == "absent" and process_gateway == "0"
        ),
    }
    try:
        request("http://127.0.0.1:18001", "/v1/models", timeout=2)
        results["isolation"]["host_18001_blocked"] = False
    except Exception:
        results["isolation"]["host_18001_blocked"] = True

    internal_auth_code = """
import json
from pathlib import Path
import urllib.error
import urllib.request

def status(key):
    headers = {} if key is None else {"Authorization": f"Bearer {key}"}
    request = urllib.request.Request(
        "http://127.0.0.1:8000/v1/models",
        headers=headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code

key = Path("/run/secrets/inference_api_key").read_text(encoding="ascii").strip()
print(json.dumps({
    "missing": status(None),
    "wrong": status("wrong-key"),
    "correct": status(key),
}))
"""
    internal_auth = json.loads(run([
        "docker",
        "exec",
        vllm_container,
        "python3",
        "-c",
        internal_auth_code,
    ]).stdout)
    results["vllm_auth"] = internal_auth

    if args.confirm_restart:
        compose("stop", "vllm", timeout=60)
        status, body = request(args.gateway_url, "/v1/models", key=key, timeout=10)
        record("upstream-unavailable.txt", body)
        results["upstream_unavailable"] = status
        restart_started = time.monotonic()
        compose("start", "vllm", timeout=60)
        deadline = time.monotonic() + args.restart_timeout_seconds
        while True:
            try:
                status, body = request(args.gateway_url, "/v1/models", key=key, timeout=5)
                if status == 200:
                    break
            except Exception:
                pass
            if time.monotonic() >= deadline:
                raise VerificationFailure("vLLM did not recover before the restart timeout")
            time.sleep(5)
        record("listing-after-restart.json", body)
        results["restart"] = {
            "status": status,
            "seconds": round(time.monotonic() - restart_started, 1),
        }
        status, body = request(args.gateway_url, "/v1/chat/completions", method="POST", key=key, body=valid)
        record("chat-after-restart.json", body)
        results["chat_after_restart"] = status
    else:
        results["restart"] = "skipped; pass --confirm-restart"

    vllm_log_result = run(["docker", "logs", vllm_container], check=False)
    gateway_log_result = run(["docker", "logs", gateway_container], check=False)
    combined_logs = (
        vllm_log_result.stdout
        + vllm_log_result.stderr
        + gateway_log_result.stdout
        + gateway_log_result.stderr
    )
    results["logs"] = {
        "secret_absent": key not in combined_logs,
        "prompts_absent": all(prompt not in combined_logs for prompt in prompts),
    }

    checks = [
        results["listing_missing_key"] == 401,
        results["listing_wrong_key"] == 401,
        results["listing"] == 200,
        results["model_present"],
        results["metrics_route"] == 404,
        results["invocations_route"] == 404,
        results["chat_missing_key"] == 401,
        results["chat"] == 200,
        results["chat_has_content"],
        results["unsupported_model"] == 404,
        results["malformed"] in (400, 422),
        results["over_context"] == 400,
        results["over_context_mentions_limit"],
        results["output_limit"] == 200,
        results["output_within_limit"],
        results["oversized"] == 413,
        results["stream"]["status"] == 200,
        results["stream"]["content_events"] > 1,
        results["stream"]["done"],
        cancellation["maximum_running"] >= 1,
        0 < cancellation["generated_before_stop"] < 128,
        cancellation["generated_during_followup"] == 0,
        cancellation["running_after_followup"] == 0,
        all(results["isolation"].values()),
        all(results["logs"].values()),
        results["vllm_auth"] == {
            "missing": 401,
            "wrong": 401,
            "correct": 200,
        },
    ]
    if args.confirm_restart:
        checks.extend([
            results["upstream_unavailable"] == 502,
            results["restart"]["status"] == 200,
            results["chat_after_restart"] == 200,
        ])
    results["pass"] = all(checks)
    (evidence / "summary.json").write_text(
        json.dumps(results, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"MEM-66 verification {'PASS' if results['pass'] else 'FAIL'}; evidence: {evidence}")
    return 0 if results["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
