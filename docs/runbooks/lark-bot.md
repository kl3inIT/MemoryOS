# Lark bot

How the chat bots' process (`memoryos-channels`, [MEM-246](../increments/active/mem-246-lark-bot/design.md)) is
provisioned on a host. It runs on staging only until the bot is accepted; production's Compose files do not define
it. Connecting a Lark app and switching a bot on come with the Lark adapter and its settings page.

## Provision a staging host

Once, as root, before the first release that carries the process is deployed. The key is made on the host and never
leaves it:

```sh
umask 077
mkdir -p /apps/memoryos/secrets/encryption
head -c 32 /dev/urandom | base64 > /apps/memoryos/secrets/encryption/channel-credential-key.txt
grep -q '^MEMORYOS_CHANNEL_CREDENTIAL_KEY_VERSION=' /apps/memoryos/.env.staging \
  || echo 'MEMORYOS_CHANNEL_CREDENTIAL_KEY_VERSION=staging-v1' >> /apps/memoryos/.env.staging
```

Without them a deployment refuses before it reserves anything: Compose requires the version and `deploy.sh` checks
that every secret file exists. Replacing the key makes every stored bot secret unreadable; enter each bot's secret
again after a new version.

## What the process uses

- The API's Redis user and object-storage identity: a turn's rights are exactly those of the API's turns.
- Its own database pool, `MEMORYOS_CHANNELS_DATABASE_POOL_SIZE` (5). `deploy.sh` counts it in the role's budget:
  API 15, Worker 10, channels 5 and 10 spare fit the default limit of 40.
- A memory limit of `MEMORYOS_CHANNELS_MEMORY_LIMIT` (1536m) and one replica.
- Health on port 8082 inside the container (`/actuator/health/readiness`); no published port.

## Deploying

`deploy.sh` starts it after the API and the Worker, stops it with the writers before the backup and migration, counts
it in verification, and restores the previous release's container on rollback. A release without its image, or an
environment whose Compose files do not define it, simply has none.

The alert `MemoryOSChannelsTelemetryMissing` fires when its telemetry is absent for five minutes.
