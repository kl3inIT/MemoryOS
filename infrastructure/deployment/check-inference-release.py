#!/usr/bin/env python3
"""Validate the model-free release/Compose boundary and optionally write serving checksums."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


REQUIRED = (
    "infrastructure/inference/managed/manifest.json",
    "infrastructure/inference/managed/runtime.py",
    "infrastructure/inference/managed/provision.py",
    "infrastructure/inference/managed/probe.py",
    "infrastructure/inference/managed/start-vllm.py",
    "infrastructure/inference/managed/start-gateway.sh",
    "infrastructure/inference/managed/nginx.conf",
    "infrastructure/inference/managed/logging.json",
    "infrastructure/deployment/compose.inference.yaml",
    "infrastructure/deployment/compose.inference.application.yaml",
    "infrastructure/deployment/compose.inference.local.yaml",
    "infrastructure/deployment/inference.env.example",
    "infrastructure/deployment/deploy-staging.sh",
    "infrastructure/deployment/inference-operations.sh",
    "infrastructure/deployment/inference-credential.py",
    "infrastructure/deployment/check-inference-release.py",
    "infrastructure/observability/compose.observability.yaml",
    "infrastructure/observability/compose.inference.yaml",
    "infrastructure/observability/prometheus.yaml",
    "infrastructure/observability/alerts.yaml",
    "infrastructure/observability/grafana/dashboards/inference.json",
)


def check(root, compose):
    for name in REQUIRED:
        path = root / name
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing regular release artifact: {name}")
        if path.suffix == ".py":
            ast.parse(path.read_text(encoding="utf-8"), filename=name)
        if path.suffix == ".json":
            json.loads(path.read_text(encoding="utf-8"))
    manifest = json.loads((root / REQUIRED[0]).read_text(encoding="utf-8"))
    for image in manifest["images"].values():
        if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", image["reference"]) or image["platform"] != "linux/amd64":
            raise ValueError("Serving images require separate immutable linux/amd64 digests")
    if manifest["model"]["tokenizerProfile"] != "smollm2-135m-12fd25f-v1":
        raise ValueError("Serving profile requires application compatibility review")
    for launcher in manifest["managedRuntime"]["launcherFiles"]:
        path = root / "infrastructure/inference/managed" / launcher["path"]
        if path.parent != root / "infrastructure/inference/managed" or path.is_symlink():
            raise ValueError("Launcher checksum escapes managed release")
        if hashlib.sha256(path.read_bytes()).hexdigest() != launcher["sha256"]:
            raise ValueError("Managed launcher checksum does not match its manifest")
    for asset in manifest["model"]["files"]:
        if not re.fullmatch(r"[0-9a-f]{64}", asset["sha256"]):
            raise ValueError("Missing pinned model asset digest")
    if compose:
        command = ["docker", "compose", "--env-file", "infrastructure/deployment/inference.env.example",
                   "-f", "infrastructure/deployment/compose.inference.yaml"]
        config = json.loads(subprocess.check_output(
            [*command, "config", "--format", "json"], cwd=root, timeout=30))
        for role, service in (("engine", "vllm"), ("gateway", "inference-gateway")):
            selected = config["services"][service]
            if selected["image"] != manifest["images"][role]["reference"] or selected.get("ports"):
                raise ValueError("Serving image/ingress differs from manifest")
            if selected.get("user") != "1654:1654" or not selected.get("read_only"):
                raise ValueError("Serving identity/root filesystem boundary changed")
            scratch = next((mount for mount in selected.get("tmpfs", []) if mount.startswith("/tmp:")), "")
            scratch_flags = set(scratch.partition(":")[2].split(","))
            expected_execution = {"exec"} if role == "engine" else {"noexec"}
            if scratch_flags.intersection({"exec", "noexec"}) != expected_execution:
                raise ValueError("Engine scratch must explicitly allow native loading; gateway scratch must remain noexec")
            for mount in selected.get("volumes", []):
                if mount["target"] == "/opt/memoryos-inference":
                    source = Path(mount["source"])
                    if source.resolve() != (root / "infrastructure/inference/managed").resolve() or not mount.get("read_only"):
                        raise ValueError("Serving launcher mount escapes release")
        engine_networks = set(config["services"]["vllm"]["networks"])
        if engine_networks != {"memoryos-inference-backend"}:
            raise ValueError("Engine must have only the private backend network")
        if not all(network.get("internal") for network in config["networks"].values()):
            raise ValueError("Serving network must not provide ordinary download egress")
        local = json.loads(subprocess.check_output(
            [*command, "-f", "infrastructure/deployment/compose.inference.local.yaml",
             "config", "--format", "json"], cwd=root, timeout=30))
        ports = local["services"]["inference-gateway"].pop("ports", [])
        if (len(ports) != 1 or ports[0].get("host_ip") != "127.0.0.1"
                or ports[0].get("target") != 8080 or str(ports[0].get("published")) != "18081"
                or ports[0].get("protocol", "tcp") != "tcp"):
            raise ValueError("Local inference must publish only the authenticated loopback gateway")
        host_network = "memoryos-inference-host"
        if local["services"]["inference-gateway"]["networks"].pop(host_network, False) is not None:
            raise ValueError("Local host bridge must attach only to the gateway without extra network options")
        host_bridge = local["networks"].pop(host_network, {})
        if (host_bridge.pop("ipam", {}) or host_bridge != {
                "name": f"{config['name']}_{host_network}", "driver": "bridge"}):
            raise ValueError("Local host ingress requires a project-scoped ordinary bridge")
        if local != config:
            raise ValueError("Local overlay must not change managed settings beyond gateway-only bridge and loopback ingress")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--compose", action="store_true", help="Parse Compose only; never start a workload")
    parser.add_argument("--write-checksums", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        check(root, args.compose)
        if args.write_checksums:
            # Every release-mounted managed file is included, not merely the required entrypoints.
            paths = set(REQUIRED)
            for directory in ("infrastructure/inference/managed", "infrastructure/observability"):
                for path in (root / directory).rglob("*"):
                    if path.is_file() and path.suffix in (".py", ".sh", ".json", ".yaml"):
                        paths.add(path.relative_to(root).as_posix())
            args.write_checksums.write_text("".join(
                hashlib.sha256((root / name).read_bytes()).hexdigest() + "  " + name + "\n"
                for name in sorted(paths)), encoding="utf-8")
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"Serving release contract failed: {error}", file=sys.stderr)
        return 1
    print("Serving artifact contract verified; no model workload was started")
    return 0


if __name__ == "__main__":
    sys.exit(main())
