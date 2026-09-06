# Staging Redis inspection

The owner expects Redis Insight to inspect the complete staging Redis database. Replace the brittle command/prefix allowlist with all-key read access, connection commands and read-only diagnostics. Permit CONFIG GET, not CONFIG SET; permit SLOWLOG GET/LEN, not RESET; permit MEMORY diagnostics, not PURGE. Keep worker/default/admin identities and production unchanged. Retain SSO and private network boundaries.

Apply ACL SETUSER to the existing inspector without changing its password or restarting Redis, save ACL state, and synchronize the mounted startup script so restart regenerates the same policy. Verify permissions through ACL DRYRUN, never by actually running destructive commands. Verify real inspector reads as well.
