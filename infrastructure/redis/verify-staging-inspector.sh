#!/bin/sh
set -eu

# Run inside the staging Redis container. Never prints authentication material.
export REDISCLI_AUTH="$(cat /run/secrets/redis_admin_password)"
admin() {
    redis-cli --tls --cacert /tmp/redis-ca.crt --user memoryos-admin --raw "$@"
}
check() {
    expected=$1
    shift
    result=$(admin ACL DRYRUN memoryos-inspector "$@")
    if [ "$expected" = allow ]; then
        [ "$result" = OK ] || { echo "FAIL allow: $1"; exit 1; }
    else
        case "$result" in
            *"no permissions"*) ;;
            *) echo "FAIL deny: $1"; exit 1 ;;
        esac
    fi
    echo "PASS $expected: $*"
}
check allow EXISTS arbitrary:staging:key
check allow SCAN 0 COUNT 100
check allow TYPE arbitrary:staging:key
check allow TTL arbitrary:staging:key
check allow PTTL arbitrary:staging:key
check allow GET arbitrary:staging:key
check allow MGET one two
check allow HSCAN arbitrary:hash 0
check allow HGETALL arbitrary:hash
check allow LRANGE arbitrary:list 0 20
check allow SSCAN arbitrary:set 0
check allow ZRANGE arbitrary:zset 0 20 WITHSCORES
check allow XLEN memoryos:execution:ingestion:operations:v1
check allow XRANGE memoryos:execution:ingestion:operations:v1 - + COUNT 10
check allow XREVRANGE memoryos:execution:ingestion:operations:v1 + - COUNT 10
check allow XINFO GROUPS memoryos:execution:ingestion:operations:v1
check allow XINFO CONSUMERS memoryos:execution:ingestion:operations:v1 memoryos:execution:ingestion:workers:v1
check allow XPENDING memoryos:execution:ingestion:operations:v1 memoryos:execution:ingestion:workers:v1
check allow INFO
check allow DBSIZE
check allow COMMAND INFO EXISTS
check allow CONFIG GET maxmemory
check allow MEMORY USAGE arbitrary:staging:key
check allow MEMORY STATS
check allow SLOWLOG GET 10
check allow SLOWLOG LEN
check allow CLIENT LIST
check allow CLIENT SETNAME inspector-verification
check allow SELECT 1
check deny SET test value
check deny DEL test
check deny UNLINK test
check deny EXPIRE test 10
check deny XADD test '*' field value
check deny XACK test group 1-0
check deny XGROUP CREATE test group 0
check deny FLUSHDB
check deny FLUSHALL
check deny CONFIG SET maxmemory 1
check deny ACL SETUSER unwanted on
check deny EVAL 'return 1' 0
check deny SLOWLOG RESET
check deny MEMORY PURGE
check deny SHUTDOWN

export REDISCLI_AUTH="$(cat /run/secrets/redis_inspector_password)"
inspect() {
    redis-cli --tls --cacert /tmp/redis-ca.crt --user memoryos-inspector --raw "$@"
}
result=$(inspect EXISTS memoryos:execution:ingestion:operations:v1)
[ "$result" = 0 ] || [ "$result" = 1 ]
inspect SCAN 0 COUNT 100
inspect XINFO GROUPS memoryos:execution:ingestion:operations:v1
inspect XPENDING memoryos:execution:ingestion:operations:v1 memoryos:execution:ingestion:workers:v1
echo 'PASS live inspector reads'
