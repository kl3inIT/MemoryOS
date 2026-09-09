#!/usr/bin/env python3
"""Install the staging Dashboards edge through NPM's supported custom include."""
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse


def run(arguments, data=None, allowed=(0,)):
    result = subprocess.run(arguments, input=data, capture_output=True, text=True)
    if result.returncode not in allowed:
        raise RuntimeError("operator command failed: " + " ".join(arguments[:4]) + "\n" + result.stderr[-2000:])
    return result


def main():
    origin = os.environ["MEMORYOS_OPENSEARCH_DASHBOARDS_PUBLIC_URL"].rstrip("/")
    parsed = urllib.parse.urlsplit(origin)
    host = parsed.hostname or ""
    if parsed.scheme != "https" or parsed.netloc != host or parsed.path or parsed.query or parsed.fragment:
        raise ValueError("expected an exact HTTPS origin without a custom port")
    if not re.fullmatch(r"[A-Za-z0-9.-]+", host):
        raise ValueError("invalid Dashboards hostname")
    container = "nginx-proxy-manager"
    prefix = ["docker", "exec", container]
    custom = "/data/nginx/custom"
    include = "include /data/nginx/custom/memoryos-search.conf;"
    existing = run(prefix + ["cat", custom + "/http.conf"], allowed=(0, 1)).stdout
    collision = run(prefix + ["grep", "-RlF", host, "/data/nginx/proxy_host"], allowed=(0, 1))
    if collision.returncode == 0:
        raise RuntimeError("hostname already managed by a normal NPM proxy host")
    previous = run(prefix + ["cat", custom + "/memoryos-search.conf"], allowed=(0, 1)).stdout
    if previous and ("# Managed by MemoryOS" not in previous or "server_name " + host + ";" not in previous):
        raise RuntimeError("refusing to replace an unrelated custom proxy")
    run(prefix + ["mkdir", "-p", custom])

    def write(name, content):
        run(["docker", "exec", "-i", container, "sh", "-c", 'cat > "$1"', "sh", custom + "/" + name], content)

    http = """# Managed by MemoryOS
server {
    listen 80;
    listen [::]:80;
    server_name __HOST__;
    include /etc/nginx/conf.d/include/letsencrypt-acme-challenge.conf;
    location / { return 301 https://$host$request_uri; }
}
""".replace("__HOST__", host)
    https = """
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name __HOST__;
    ssl_certificate /etc/letsencrypt/live/memoryos-search/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/memoryos-search/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    location / {
        resolver 127.0.0.11 valid=10s;
        set $dashboards memoryos-opensearch-dashboards:5601;
        proxy_pass https://$dashboards;
        proxy_ssl_trusted_certificate /data/nginx/custom/memoryos-search-ca.crt;
        proxy_ssl_verify on;
        proxy_ssl_server_name on;
        proxy_ssl_name memoryos-opensearch-dashboards;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 90s;
    }
}
""".replace("__HOST__", host)
    try:
        secret_dir = Path(os.environ.get("MEMORYOS_OPENSEARCH_SECRET_DIRECTORY", "/apps/memoryos/secrets/opensearch"))
        run(["openssl", "x509", "-in", str(secret_dir / "ca.crt"), "-noout"])
        write("memoryos-search-ca.crt", (secret_dir / "ca.crt").read_text())
        write("memoryos-search.conf", previous or http)
        if include not in existing.splitlines():
            write("http.conf", existing.rstrip() + "\n" + include + "\n")
        run(prefix + ["nginx", "-t"])
        run(prefix + ["nginx", "-s", "reload"])
        # Reuses the existing ACME account; never reads or exports its private key.
        result = run(prefix + ["/opt/certbot/bin/certbot", "certonly", "-n", "--config", "/etc/letsencrypt.ini",
                               "--work-dir", "/data/letsencrypt-work", "--logs-dir", "/data/logs",
                               "--cert-name", "memoryos-search", "--authenticator", "webroot",
                               "--preferred-challenges", "http", "--keep-until-expiring", "--domains", host])
        print(result.stdout[-1500:])
        write("memoryos-search.conf", http + https)
        run(prefix + ["nginx", "-t"])
        run(prefix + ["nginx", "-s", "reload"])
    except Exception:
        write("http.conf", existing)
        write("memoryos-search.conf", previous)
        run(prefix + ["nginx", "-t"])
        run(prefix + ["nginx", "-s", "reload"])
        raise

    # NPM's database scheduler does not own this custom certificate. Give it an
    # explicit, stable renewal command; preserve every other operator cron entry.
    renewal = Path("/apps/memoryos/renew-search-certificate.sh")
    renewal.write_text((Path(__file__).parent / "renew-dashboards-certificate.sh").read_text(), encoding="utf-8")
    renewal.chmod(0o700)
    cron = run(["crontab", "-l"], allowed=(0, 1)).stdout
    entry = "17 3,15 * * * /apps/memoryos/renew-search-certificate.sh >> /apps/memoryos/search-certificate-renewal.log 2>&1"
    if entry not in cron.splitlines():
        cron = cron.rstrip() + "\n" + entry + "\n"
    internal_renewal = Path("/apps/memoryos/renew-internal-search-certificates.py")
    internal_renewal.write_text((Path(__file__).parent / "provision-staging.py").read_text(), encoding="utf-8")
    internal_renewal.chmod(0o700)
    internal_entry = "41 3 * * * /usr/bin/python3 /apps/memoryos/renew-internal-search-certificates.py --renew-certificates >> /apps/memoryos/internal-search-certificate-renewal.log 2>&1"
    if internal_entry not in cron.splitlines():
        cron = cron.rstrip() + "\n" + internal_entry + "\n"
    run(["crontab", "-"], cron)
    print(json.dumps({"dashboards_origin": origin, "nginx_configuration": "validated", "renewal": "twice daily operator cron"}))


if __name__ == "__main__":
    main()
