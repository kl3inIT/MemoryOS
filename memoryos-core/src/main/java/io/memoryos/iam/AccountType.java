package io.memoryos.iam;

/**
 * Persisted actor classification. Authorization is granted only through IAM groups.
 */
public enum AccountType {
    /** A person, admitted by a sign-in. */
    STANDARD,
    /**
     * An account of the Tenant's own setup that no one signs in as. Only the chat bots created them (MEM-246); the
     * accounts of bots made before their removal stay, out of the Users list and the people pickers (ADR 0032).
     */
    SERVICE
}
