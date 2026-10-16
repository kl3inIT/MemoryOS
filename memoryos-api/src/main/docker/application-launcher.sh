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

# MEM-244: a JDK AOT cache for this release, when the deployment mounts a directory for it. A cache records the
# classes a start loads and links, and is read only by a JVM started with the options it was made with, which is why
# it is made by this image on this host: the options are the image's JAVA_TOOL_OPTIONS and the container's memory
# limit. The option goes on the command line, not into JAVA_TOOL_OPTIONS, so the Tika child JVMs do not take it. A
# cache that is missing or does not match is ignored by the JVM; the application then starts as it did before.
MEMORYOS_JAVA_AOT=
if [ -n "${MEMORYOS_AOT_CACHE_DIR:-}" ] && [ -n "${MEMORYOS_RELEASE:-}" ]; then
    aot_cache="$MEMORYOS_AOT_CACHE_DIR/${MEMORYOS_APPLICATION_JAR%.jar}-$MEMORYOS_RELEASE.aot"
    if [ "${MEMORYOS_AOT_TRAIN:-false}" = true ]; then
        # The training start: the context is built and the JVM exits, writing the cache as it does. The directory
        # is a volume owned by root; the JVM runs as memoryos.
        chmod 1777 "$MEMORYOS_AOT_CACHE_DIR"
        rm -f "$aot_cache.tmp" "$aot_cache.tmp.config"
        MEMORYOS_JAVA_AOT="-XX:AOTCacheOutput=$aot_cache.tmp -Dspring.context.exit=onRefresh"
        export MEMORYOS_JAVA_AOT
        # shellcheck disable=SC2016
        su -p -s /bin/sh memoryos -c 'timeout 240 java $MEMORYOS_JAVA_AOT -jar "$MEMORYOS_APPLICATION_JAR"'
        test -s "$aot_cache.tmp"
        mv -f "$aot_cache.tmp" "$aot_cache"
        rm -f "$aot_cache.tmp.config"
        # This release's cache and the one before it, for a rollback; a cache is about 200 MB.
        # shellcheck disable=SC2012
        ls -t "$MEMORYOS_AOT_CACHE_DIR"/*.aot | tail -n +3 | xargs -r rm -f
        exit 0
    fi
    if [ -r "$aot_cache" ]; then
        MEMORYOS_JAVA_AOT="-XX:AOTCache=$aot_cache"
    fi
fi
export MEMORYOS_JAVA_AOT

# The jar name has to expand inside the shell that runs as memoryos, not before su hands over; the AOT option is
# left unquoted there because it is empty or one option.
# shellcheck disable=SC2016,SC2086
exec su -p -s /bin/sh memoryos -c 'exec java $MEMORYOS_JAVA_AOT -jar "$MEMORYOS_APPLICATION_JAR"'
