-- ActorId moved from io.memoryos.iam.identity to the shared kernel io.memoryos.shared (ADR 0015). The signed-in
-- principal is stored Java-serialized in the session, so a session written by an older binary cannot be read by this
-- one. Dropping the sessions (attributes cascade) forces a deliberate re-login, as V14 did for the previous move, and
-- forbids a mixed-version API rollout.
DELETE FROM spring_session;
