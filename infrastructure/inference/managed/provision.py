#!/usr/bin/env python3
"""Provision the manifest's exact asset allowlist; serving never downloads."""

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import ssl
import stat
import sys
import tempfile
import urllib.parse
import urllib.request

from runtime import (RuntimeFailure, TEMPLATE_NAME, deadline, load_manifest,
                     regular_file, safe_path, verify_assets, verify_file)


RESERVE_BYTES = 4 * 1024 * 1024 * 1024


def check_download_url(url):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ""
    # HF serves LFS objects through signed hf.co CAS/CDN redirects.
    if (parsed.scheme != "https" or parsed.port not in (None, 443)
            or parsed.username is not None or parsed.password is not None
            or parsed.fragment or not (host == "huggingface.co" or host.endswith(".hf.co"))):
        raise RuntimeFailure("DOWNLOAD_ORIGIN_REJECTED")


class AssetRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self):
        self.redirects = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.redirects += 1
        if self.redirects > 5:
            raise RuntimeFailure("DOWNLOAD_REDIRECT_LIMIT")
        check_download_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def free_space(path, required):
    if shutil.disk_usage(path).free < required:
        raise RuntimeFailure("INSUFFICIENT_ASSET_FILESYSTEM_SPACE")


def download(url, destination, item):
    check_download_url(url)
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), AssetRedirects(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    request = urllib.request.Request(url, headers={"Accept-Encoding": "identity", "User-Agent": "MemoryOS-managed-provision/1"})
    digest = hashlib.sha256()
    count = 0
    with opener.open(request, timeout=15) as response:
        if response.status != 200 or response.headers.get("Content-Encoding", "identity") != "identity":
            raise RuntimeFailure("DOWNLOAD_RESPONSE_REJECTED")
        length = response.headers.get("Content-Length")
        if length is not None and (not length.isdigit() or int(length) != item["sizeBytes"]):
            raise RuntimeFailure("DOWNLOAD_SIZE_MISMATCH")
        with destination.open("xb") as output:
            while True:
                # Read at most one byte beyond the exact bound; never persist it.
                chunk = response.read1(min(1024 * 1024, item["sizeBytes"] - count + 1))
                if not chunk:
                    break
                count += len(chunk)
                if count > item["sizeBytes"]:
                    raise RuntimeFailure("DOWNLOAD_SIZE_MISMATCH")
                free_space(destination.parent, RESERVE_BYTES + len(chunk))
                output.write(chunk)
                digest.update(chunk)
            output.flush()
            os.fsync(output.fileno())
    if count != item["sizeBytes"] or digest.hexdigest() != item["sha256"]:
        raise RuntimeFailure("DOWNLOAD_CHECKSUM_OR_SIZE_MISMATCH")
    destination.chmod(0o444)


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def provision(manifest, assets_root):
    if os.name != "posix":
        raise RuntimeFailure("ATOMIC_PROVISIONING_REQUIRES_POSIX")
    import fcntl

    root = safe_path(assets_root)
    root.mkdir(mode=0o755, parents=True, exist_ok=True)
    # The publication root must be owned by the operator, never writable by peers.
    metadata = root.stat()
    if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o022:
        raise RuntimeFailure("ASSET_ROOT_OWNER_OR_MODE_INVALID")
    destination = safe_path(root / manifest["model"]["revision"])
    if destination.exists():
        verify_assets(manifest, root)
        return "ASSETS_ALREADY_VERIFIED"
    free_space(root, max(16 * 1024 * 1024 * 1024,
                         RESERVE_BYTES + manifest["model"]["totalAssetBytesIncludingTemplate"]))
    lock = root / ".provision.lock"
    descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    staging = None
    try:
        metadata = os.fstat(descriptor)
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1
                or metadata.st_uid != os.geteuid() or stat.S_IMODE(metadata.st_mode) != 0o600):
            raise RuntimeFailure("PROVISION_LOCK_OWNER_OR_MODE_INVALID")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeFailure("ASSET_PROVISIONING_BUSY") from None
        # A concurrent publication cannot be replaced, including after taking the lock.
        if destination.exists():
            verify_assets(manifest, root)
            return "ASSETS_ALREADY_VERIFIED"
        staging = Path(tempfile.mkdtemp(prefix=".provision-", dir=root))
        with deadline(900):
            for item in manifest["model"]["files"]:
                download(manifest["model"]["assetBaseUrl"] + item["path"], staging / item["path"], item)
        tokenizer = json.loads(regular_file(staging / "tokenizer_config.json", 1024 * 1024))
        template = tokenizer["chat_template"].encode("utf-8")
        template_item = manifest["model"]["chatTemplate"]
        if len(template) != template_item["sizeBytes"] or hashlib.sha256(template).hexdigest() != template_item["sha256"]:
            raise RuntimeFailure("TEMPLATE_CHECKSUM_OR_SIZE_MISMATCH")
        with (staging / TEMPLATE_NAME).open("xb") as output:
            output.write(template)
            output.flush()
            os.fsync(output.fileno())
        (staging / TEMPLATE_NAME).chmod(0o444)
        for item in manifest["model"]["files"]:
            verify_file(staging / item["path"], item)
        verify_file(staging / TEMPLATE_NAME, template_item)
        staging.chmod(0o555)
        sync_directory(staging)
        # Lock + operator-owned root exclude cooperating publishers and untrusted writers.
        # Refuse every preexisting destination; never replace current/previous revisions.
        if destination.exists() or destination.is_symlink():
            raise RuntimeFailure("REVISION_ALREADY_EXISTS")
        staging.rename(destination)
        staging = None
        sync_directory(root)
        verify_assets(manifest, root)
        return "ASSETS_PUBLISHED_AND_VERIFIED"
    finally:
        try:
            if staging is not None:
                # Only this invocation's unpublished staging directory is disposable.
                staging.chmod(0o700)
                shutil.rmtree(staging)
        finally:
            # Closing releases flock even after a killed process; retain the stable inode.
            os.close(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--assets-root", required=True, type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        manifest = load_manifest(args.manifest)
        if args.verify_only:
            verify_assets(manifest, args.assets_root)
            result = "ASSETS_VERIFIED"
        else:
            result = provision(manifest, args.assets_root)
        print(json.dumps({"result": result, "revision": manifest["model"]["revision"]}))
        return 0
    except RuntimeFailure as error:
        print(f"Inference provisioning failed: {error}", file=sys.stderr)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, http.client.HTTPException):
        print("Inference provisioning failed: ASSET_IO_OR_FORMAT_ERROR", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
