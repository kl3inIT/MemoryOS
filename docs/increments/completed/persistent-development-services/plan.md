# Persistent local development services plan

Design: [design.md](design.md).

- [x] Add the named PostgreSQL and Redis Compose services with persistent volumes and loopback-only ports.
- [x] Disable direct-runtime Arconia Dev Services and retain explicit local API/Worker connection defaults.
- [x] Replace the development runbook's disposable-service lifecycle and add stable start, stop, and reset commands.
- [x] Verify Compose health, named container/volume identity, direct API/Worker readiness, and focused configuration checks.
