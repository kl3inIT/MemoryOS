package io.memoryos.iam.audit;

/** Whether the recorded attempt changed anything, as Onyx distinguishes an operational error from a refusal. */
public enum AuditOutcome {
    SUCCESS,
    /** The attempt was allowed but did not complete: a sign-in whose invitation had expired, for example. */
    FAILURE,
    /** Authority was refused. */
    DENIED
}
