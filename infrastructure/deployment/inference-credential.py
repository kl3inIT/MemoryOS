#!/usr/bin/env python3
"""Revision-checked, file-only inference BYOK handoff; no credential output or retries."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Redirect refused")


def restricted_file(path, maximum=16384):
    path = Path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise ValueError("A regular owner-only input file is required")
    if info.st_size > maximum:
        raise ValueError("Input file exceeds its bound")
    return path.read_text(encoding="utf-8")


def read_key(path):
    key = restricted_file(path, 65).removesuffix("\n")
    if not re.fullmatch(r"[0-9a-f]{64}", key):
        raise ValueError("Expected the managed 64-hex inference key format")
    return key


def handoff(request_path, key_path=None, *, verify_receipt=None, check_only=False):
    request = json.loads(restricted_file(request_path))
    origin = request["applicationOrigin"]
    parsed = urllib.parse.urlsplit(origin)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("Use the exact HTTPS application origin")
    provider_id = str(uuid.UUID(request["providerId"]))
    revision = request["providerRevision"]
    if verify_receipt is not None:
        receipt = json.loads(restricted_file(verify_receipt))
        if receipt["providerId"] != provider_id or not receipt["credentialConfigured"]:
            raise ValueError("Handoff receipt does not match the explicit provider")
        revision = receipt["providerRevision"]
    if type(revision) is not int or revision < 1:
        raise ValueError("An explicit positive provider revision is required")
    cookie = restricted_file(request["sessionCookieFile"]).strip()
    if not cookie or "\n" in cookie or "\r" in cookie:
        raise ValueError("Expected a single Cookie header in the protected session file")
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))

    def call(path, body=None):
        headers = {"Accept": "application/json", "Cookie": cookie}
        data = None
        if body is not None:
            headers.update({"Content-Type": "application/json", "X-MemoryOS-CSRF": "1"})
            data = json.dumps(body).encode()
        req = urllib.request.Request(origin.rstrip("/") + path, data=data, headers=headers,
                                     method="PUT" if body is not None else "GET")
        with opener.open(req, timeout=15) as response:
            raw = response.read(262145)
            if len(raw) > 262144:
                raise ValueError("Catalog response exceeds bound")
            return json.loads(raw)

    providers = call("/api/chat/providers")
    provider = next((item for item in providers if item["id"] == provider_id), None)
    if provider is None or provider["revision"] != revision:
        raise ValueError("Provider missing or revision changed; reconcile before retry")
    if (provider["adapterType"] != "openai" or not provider["enabled"]
            or provider["baseUrl"] != request["expectedProviderBaseUrl"]
            or provider["baseUrl"] != "http://inference-gateway:8080/v1"):
        raise ValueError("Handoff requires the explicit enabled managed provider endpoint")
    if check_only or verify_receipt is not None:
        if verify_receipt is not None and not provider["credentialConfigured"]:
            raise ValueError("The handed-off credential was removed")
        if check_only:
            read_key(key_path)
        return {"providerId": provider_id, "providerRevision": revision,
                "credentialConfigured": provider["credentialConfigured"]}
    body = {name: provider[name] for name in (
        "name", "adapterType", "baseUrl", "enabled", "isPublic", "groupIds", "personaIds")}
    body["credential"] = {"action": "REPLACE", "value": read_key(key_path)}
    updated = call(f"/api/chat/providers/{provider_id}?revision={revision}", body)
    if updated["revision"] != revision + 1 or not updated["credentialConfigured"]:
        raise ValueError("Unexpected credential update result; admission must remain blocked")
    current = next((item for item in call("/api/chat/providers") if item["id"] == provider_id), None)
    if current != updated:
        raise ValueError("Provider changed during handoff; admission must remain blocked")
    return {"providerId": provider_id, "previousRevision": revision,
            "providerRevision": updated["revision"], "credentialConfigured": True}


def install_key(source, destination):
    key = read_key(source)
    destination = Path(destination)
    previous = destination.lstat()
    if not stat.S_ISREG(previous.st_mode) or stat.S_IMODE(previous.st_mode) != 0o400:
        raise ValueError("Existing key must be a regular 0400 file")
    if previous.st_uid != 1654 or previous.st_gid != 1654:
        raise ValueError("Existing key must belong to serving UID/GID 1654")
    if key == read_key(destination):
        raise ValueError("Replacement key must differ")
    temporary = destination.with_name(destination.name + ".rotation-new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii") as output:
            output.write(key)
            output.flush()
            os.fchown(output.fileno(), 1654, 1654)
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    replace = commands.add_parser("handoff")
    replace.add_argument("--request", required=True)
    replace.add_argument("--key-file", required=True)
    preflight = commands.add_parser("preflight")
    preflight.add_argument("--request", required=True)
    preflight.add_argument("--key-file", required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--request", required=True)
    verify.add_argument("--receipt", required=True)
    install = commands.add_parser("install")
    install.add_argument("--new-key-file", required=True)
    install.add_argument("--key-file", required=True)
    same = commands.add_parser("same")
    same.add_argument("--key-file", required=True)
    same.add_argument("--other-key-file", required=True)
    args = parser.parse_args()
    try:
        if not hasattr(signal, "SIGALRM"):
            raise ValueError("Credential operations require the POSIX deployment host")
        def timed_out(_signum, _frame):
            raise TimeoutError("Credential operation deadline exceeded")
        signal.signal(signal.SIGALRM, timed_out)
        signal.alarm(60)
        if args.command == "handoff":
            print(json.dumps(handoff(args.request, args.key_file)))
        elif args.command == "preflight":
            handoff(args.request, args.key_file, check_only=True)
        elif args.command == "verify":
            handoff(args.request, verify_receipt=args.receipt)
        elif args.command == "same":
            return 0 if read_key(args.key_file) == read_key(args.other_key_file) else 1
        else:
            install_key(args.new_key_file, args.key_file)
    except urllib.error.HTTPError as error:
        print(f"Credential operation refused (HTTP {error.code}); keep admission blocked", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, StopIteration, urllib.error.URLError):
        print("Credential operation failed; keep admission blocked and reconcile protected inputs", file=sys.stderr)
        return 1
    finally:
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
