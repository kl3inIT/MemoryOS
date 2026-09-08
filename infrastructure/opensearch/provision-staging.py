#!/usr/bin/env python3
"""Create deployment-owned TLS/credentials; reconcile only managed Security YAML."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

IMAGE = "opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509"
SOURCE = Path(__file__).resolve().parent


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("provisioning command failed: " + args[0])
    return result.stdout


def write(path, text, mode=0o600):
    path.write_text(text, encoding="utf-8")
    path.chmod(mode)


def certificate(directory, name, subject, extensions):
    run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(directory / (name + ".key")))
    run("openssl", "req", "-new", "-key", str(directory / (name + ".key")), "-subj", subject, "-out", str(directory / (name + ".csr")))
    write(directory / (name + ".ext"), extensions)
    run("openssl", "x509", "-req", "-in", str(directory / (name + ".csr")), "-CA", str(directory / "ca.crt"),
        "-CAkey", str(directory / "ca.key"), "-CAcreateserial", "-days", "365", "-sha256",
        "-extfile", str(directory / (name + ".ext")), "-out", str(directory / (name + ".crt")))


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
    os.umask(0o077)
    directory = Path(os.environ.get("MEMORYOS_OPENSEARCH_SECRET_DIRECTORY", "/apps/memoryos/secrets/opensearch")).resolve()
    issuer = os.environ.get("MEMORYOS_OPENSEARCH_OIDC_ISSUER", "https://auth.kl3in.tech/realms/memoryos").rstrip("/")
    if not re.fullmatch(r"https://[A-Za-z0-9.:-]+/realms/[A-Za-z0-9_-]+", issuer):
        raise ValueError("expected an exact HTTPS Keycloak realm issuer")
    if os.getuid() != 1000:
        raise RuntimeError("run as deployment UID 1000 to match the pinned OpenSearch image")
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
            certificate(temporary, "node", "/CN=memoryos-opensearch",
                        "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                        "extendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:opensearch,DNS:memoryos-opensearch,DNS:localhost,IP:127.0.0.1\n")
            certificate(temporary, "admin", "/CN=memoryos-search-admin",
                        "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n")
            for filename in ("service-password.txt", "dashboards-password.txt", "oidc-client-secret.txt", "cookie-password.txt"):
                write(temporary / filename, run("openssl", "rand", "-hex", "32").strip() + "\n")
            write(temporary / "health.curl", 'user = "memoryos-service:' + (temporary / "service-password.txt").read_text().strip() + '"\n')
            (temporary / "ca.crt").chmod(0o444)
            reconcile(temporary, issuer)
            temporary.rename(directory)
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)
        print("OpenSearch TLS and credentials provisioned; values withheld")
        return
    reconcile(directory, issuer)
    print("OpenSearch Security YAML reconciled; existing TLS and credentials preserved")


if __name__ == "__main__":
    main()
