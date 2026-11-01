#!/bin/sh
set -eu

# Secrets arrive as files mounted by Compose, which the launcher reads. Nothing is fetched over the
# network at start, so a service the deployment does not control cannot keep the process from
# starting. Developer machines still use Infisical; see docs/increments/active/server-secrets-as-files.
: "${MEMORYOS_APPLICATION_JAR:?MEMORYOS_APPLICATION_JAR is required}"

exec /usr/local/bin/memoryos-launcher
