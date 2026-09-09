#!/usr/bin/env python3
"""Create deployment-owned TLS/credentials; reconcile only managed Security YAML."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

IMAGE = "opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509"
SOURCE = Path(__file__).resolve().parent
LEAF_CERTIFICATES = {
    "node": ("/CN=memoryos-opensearch",
             "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
             "extendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:opensearch,DNS:memoryos-opensearch,DNS:localhost,IP:127.0.0.1\n"),
    "admin": ("/CN=memoryos-search-admin",
              "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n"),
    "dashboards": ("/CN=memoryos-opensearch-dashboards",
                   "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                   "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:memoryos-opensearch-dashboards,DNS:localhost,IP:127.0.0.1\n"),
}


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("provisioning command failed: " + args[0])
    return result.stdout


def write(path, text, mode=0o600):
    path.write_text(text, encoding="utf-8")
    path.chmod(mode)


def write_dashboards_bootstrap_config(directory):
    password = (directory / "dashboards-password.txt").read_text(encoding="utf-8").strip()
    if not password or any(character in password for character in '\\"\r\n'):
        raise RuntimeError("invalid Dashboards password for curl configuration")
    write(directory / "dashboards-bootstrap.curl", 'user = "memoryos-dashboards:' + password + '"\n')


def certificate(directory, name, subject, extensions, authority=None):
    authority = authority or directory
    run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(directory / (name + ".key")))
    run("openssl", "req", "-new", "-key", str(directory / (name + ".key")), "-subj", subject, "-out", str(directory / (name + ".csr")))
    write(directory / (name + ".ext"), extensions)
    run("openssl", "x509", "-req", "-in", str(directory / (name + ".csr")), "-CA", str(authority / "ca.crt"),
        "-CAkey", str(authority / "ca.key"), "-set_serial", "0x" + run("openssl", "rand", "-hex", "16").strip(), "-days", "365", "-sha256",
        "-extfile", str(directory / (name + ".ext")), "-out", str(directory / (name + ".crt")))


def restart_and_wait(container):
    run("docker", "restart", container)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if run("docker", "inspect", "--format", "{{.State.Health.Status}}", container).strip() == "healthy":
            return
        time.sleep(2)
    raise RuntimeError("certificate reload health check failed: " + container)


def renew_certificates(directory, within_days=30):
    # A single renewal process owns the leaf replacement and its rollback.
    import fcntl
    with (directory / ".certificate-renewal.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        due = []
        for name in LEAF_CERTIFICATES:
            run("openssl", "verify", "-no_check_time", "-CAfile", str(directory / "ca.crt"), str(directory / (name + ".crt")))
            result = subprocess.run(["openssl", "x509", "-checkend", str(within_days * 86400), "-noout",
                                     "-in", str(directory / (name + ".crt"))], capture_output=True)
            if result.returncode:
                due.append(name)
        if not due:
            print("Internal Search certificates remain valid beyond the renewal window")
            return []
        with tempfile.TemporaryDirectory(prefix=".certificate-renewal-", dir=directory) as name:
            temporary = Path(name)
            for leaf in due:
                certificate(temporary, leaf, *LEAF_CERTIFICATES[leaf], authority=directory)
                run("openssl", "verify", "-CAfile", str(directory / "ca.crt"), str(temporary / (leaf + ".crt")))
            backup = directory / "certificate-backups" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            backup.mkdir(parents=True, mode=0o700)
            names = [leaf + extension for leaf in due for extension in (".crt", ".key")]
            for filename in names:
                shutil.copy2(directory / filename, backup / filename)
            containers = []
            if "node" in due:
                containers.append("memoryos-opensearch")
            if "dashboards" in due:
                containers.append("memoryos-opensearch-dashboards")
            try:
                for filename in names:
                    os.replace(temporary / filename, directory / filename)
                for container in containers:
                    restart_and_wait(container)
            except Exception:
                for filename in names:
                    shutil.copy2(backup / filename, directory / filename)
                for container in containers:
                    restart_and_wait(container)
                raise
        # The admin DN and CA remain unchanged. Bootstrap loads this new leaf
        # on its next invocation; no security role/config rewrite is necessary.
        print("Internal Search certificates renewed: " + ", ".join(due))
        return due


def reconcile(directory, issuer):
    security = directory / "security"
    security.mkdir(mode=0o700, exist_ok=True)
    for filename in ("roles.yml", "action_groups.yml"):
        default = run("docker", "run", "--rm", "--network", "none", "--entrypoint", "cat", IMAGE,
                      "/usr/share/opensearch/config/opensearch-security/" + filename)
        if filename == "roles.yml":
            default += "\n" + "\n".join((SOURCE / "security/roles.yml").read_text().splitlines()[3:]) + "\n"
        write(security / filename, default)
    for filename, kind in (("tenants.yml", "tenants"), ("nodes_dn.yml", "nodesdn"), ("allowlist.yml", "allowlist")):
        write(security / filename, json.dumps({"_meta": {"type": kind, "config_version": 2}}))
    write(security / "audit.yml", json.dumps({"_meta": {"type": "audit", "config_version": 2}, "config": {"enabled": False}}))
    write(security / "config.yml", (SOURCE / "security/config.yml").read_text().replace("__OIDC_ISSUER__", issuer))
    write(security / "roles_mapping.yml", (SOURCE / "security/roles_mapping.yml").read_text())
    users = {"_meta": {"type": "internalusers", "config_version": 2}}
    for username, filename in (("memoryos-service", "service-password.txt"), ("memoryos-dashboards", "dashboards-password.txt")):
        output = run("docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/sh",
                     "-v", str(directory) + ":/provision:ro", IMAGE, "-c",
                     '/usr/share/opensearch/plugins/opensearch-security/tools/hash.sh -p "$(cat /provision/' + filename + ')"')
        hashes = [line.strip() for line in output.splitlines() if re.fullmatch(r"\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}", line.strip())]
        if len(hashes) != 1:
            raise RuntimeError("password hash generation failed")
        users[username] = {"hash": hashes[0], "reserved": True, "backend_roles": []}
    write(security / "internal_users.yml", json.dumps(users, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--renew-certificates", action="store_true")
    parser.add_argument("--within-days", type=int, default=30)
    arguments = parser.parse_args()
    if not 1 <= arguments.within_days <= 366:
        raise ValueError("certificate renewal window must be between 1 and 366 days")
    os.umask(0o077)
    directory = Path(os.environ.get("MEMORYOS_OPENSEARCH_SECRET_DIRECTORY", "/apps/memoryos/secrets/opensearch")).resolve()
    issuer = os.environ.get("MEMORYOS_OPENSEARCH_OIDC_ISSUER", "https://auth.kl3in.tech/realms/memoryos").rstrip("/")
    if not re.fullmatch(r"https://[A-Za-z0-9.:-]+/realms/[A-Za-z0-9_-]+", issuer):
        raise ValueError("expected an exact HTTPS Keycloak realm issuer")
    if os.getuid() != 1000:
        raise RuntimeError("run as deployment UID 1000 to match the pinned OpenSearch image")
    if arguments.renew_certificates:
        renew_certificates(directory, arguments.within_days)
        return
    required = ["ca.crt", "ca.key", "node.crt", "node.key", "admin.crt", "admin.key", "service-password.txt",
                "dashboards-password.txt", "oidc-client-secret.txt", "cookie-password.txt", "health.curl"]
    if directory.exists():
        if not all((directory / name).is_file() and (directory / name).stat().st_size for name in required):
            raise RuntimeError("partial secret set; restore the complete set before retrying")
    else:
        directory.parent.mkdir(parents=True, exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix=".opensearch-init-", dir=directory.parent))
        try:
            run("openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-keyout", str(temporary / "ca.key"),
                "-out", str(temporary / "ca.crt"), "-sha256", "-days", "3650", "-subj", "/CN=MemoryOS Search CA")
            for leaf, definition in LEAF_CERTIFICATES.items():
                certificate(temporary, leaf, *definition)
            for filename in ("service-password.txt", "dashboards-password.txt", "oidc-client-secret.txt", "cookie-password.txt"):
                write(temporary / filename, run("openssl", "rand", "-hex", "32").strip() + "\n")
            write(temporary / "health.curl", 'user = "memoryos-service:' + (temporary / "service-password.txt").read_text().strip() + '"\n')
            write_dashboards_bootstrap_config(temporary)
            (temporary / "ca.crt").chmod(0o444)
            reconcile(temporary, issuer)
            temporary.rename(directory)
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)
        print("OpenSearch TLS and credentials provisioned; values withheld")
        return
    if not (directory / "dashboards.crt").exists() and not (directory / "dashboards.key").exists():
        certificate(directory, "dashboards", *LEAF_CERTIFICATES["dashboards"])
    if not (directory / "dashboards.crt").is_file() or not (directory / "dashboards.key").is_file():
        raise RuntimeError("partial Dashboards TLS certificate pair")
    write_dashboards_bootstrap_config(directory)
    reconcile(directory, issuer)
    print("OpenSearch Security YAML reconciled; existing TLS and credentials preserved")


if __name__ == "__main__":
    main()
