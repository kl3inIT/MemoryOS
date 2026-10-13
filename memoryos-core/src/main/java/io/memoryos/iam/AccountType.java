package io.memoryos.iam;

/**
 * Persisted actor classification. Authorization is granted only through IAM groups.
 */
public enum AccountType {
    /** A person, admitted by a sign-in. */
    STANDARD,
    /** An account of the Tenant's own setup, such as a channel's bot ({@link ServiceAccounts}). */
    SERVICE
}
