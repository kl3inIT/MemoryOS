#!/bin/sh
set -eu

# Run explicitly after the node starts; no admin certificate is mounted in the node.
#
# securityadmin.sh writes into the cluster it is told the name of, and refuses when the name does
# not match the running one. The name therefore comes from the environment that started the node,
# never from this file, which both environments share.
: "${MEMORYOS_SEARCH_CLUSTER_NAME:?MEMORYOS_SEARCH_CLUSTER_NAME is required}"
attempt=0
until curl --silent --output /dev/null --max-time 3 \
    --cacert /bootstrap/tls/ca.crt https://opensearch:9200; do
    attempt=$((attempt + 1))
    test "$attempt" -lt 60 || exit 1
    sleep 3
done

exec /usr/share/opensearch/plugins/opensearch-security/tools/securityadmin.sh \
    -h opensearch -p 9200 -cn "$MEMORYOS_SEARCH_CLUSTER_NAME" \
    -cd /bootstrap/security \
    -cacert /bootstrap/tls/ca.crt \
    -cert /bootstrap/tls/admin.crt \
    -key /bootstrap/tls/admin.key
