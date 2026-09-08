#!/bin/sh
set -eu

# Run explicitly after the node starts; no admin certificate is mounted in the node.
attempt=0
until curl --silent --output /dev/null --max-time 3 \
    --cacert /bootstrap/tls/ca.crt https://opensearch:9200; do
    attempt=$((attempt + 1))
    test "$attempt" -lt 60 || exit 1
    sleep 3
done

exec /usr/share/opensearch/plugins/opensearch-security/tools/securityadmin.sh \
    -h opensearch -p 9200 -cn memoryos-search-staging \
    -cd /bootstrap/security \
    -cacert /bootstrap/tls/ca.crt \
    -cert /bootstrap/tls/admin.crt \
    -key /bootstrap/tls/admin.key
