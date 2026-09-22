#!/bin/sh
set -eu

# A secret reaches the process through a file, never through the environment of the container:
# an environment variable is visible in `docker inspect`, in a crash log and in /proc/<pid>/environ.
# Every MEMORYOS_<NAME>_FILE that names a readable file becomes MEMORYOS_<NAME> here, so a new
# secret needs an entry in the environment file and a mount, not a change to this script.
#
# Only MEMORYOS_ names are read. Unrelated tools set variables like SSL_CERT_FILE, and exporting
# SSL_CERT from one would be both wrong and hard to notice.
for secret_variable in $(env | sed -n 's/^\(MEMORYOS_[A-Z0-9_]*_FILE\)=.*/\1/p'); do
    # The Redis authority is staged as a file reference rather than its contents; see below.
    [ "$secret_variable" = MEMORYOS_REDIS_TLS_CA_FILE ] && continue
    eval "secret_path=\${$secret_variable}"
    [ -n "$secret_path" ] || continue
    test -r "$secret_path"
    secret_value=$(cat "$secret_path")
    test -n "$secret_value"
    export "${secret_variable%_FILE}=$secret_value"
done
unset secret_variable secret_path secret_value

if [ -n "${MEMORYOS_REDIS_TLS_CA_FILE:-}" ]; then
    test -r "$MEMORYOS_REDIS_TLS_CA_FILE"
    staged_ca=/tmp/memoryos-redis-ca.crt
    cp "$MEMORYOS_REDIS_TLS_CA_FILE" "$staged_ca"
    chmod 0444 "$staged_ca"
    MEMORYOS_REDIS_TLS_CA_CERTIFICATE=file:$staged_ca
    export MEMORYOS_REDIS_TLS_CA_CERTIFICATE
fi

unset INFISICAL_TOKEN
# The jar name has to expand inside the shell that runs as memoryos, not before su hands over.
# shellcheck disable=SC2016
exec su -p -s /bin/sh memoryos -c 'exec java -jar "$MEMORYOS_APPLICATION_JAR"'
