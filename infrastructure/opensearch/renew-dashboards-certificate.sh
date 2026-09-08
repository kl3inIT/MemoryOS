#!/bin/sh
set -eu
PATH=/usr/local/bin:/usr/bin:/bin
export PATH

exec docker exec nginx-proxy-manager /opt/certbot/bin/certbot renew \
    --non-interactive \
    --config /etc/letsencrypt.ini \
    --work-dir /tmp/letsencrypt-lib \
    --logs-dir /data/logs \
    --cert-name memoryos-search \
    --no-random-sleep-on-renew \
    --deploy-hook 'nginx -t && nginx -s reload'
