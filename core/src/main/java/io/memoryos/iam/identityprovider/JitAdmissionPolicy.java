package io.memoryos.iam.identityprovider;

/**
 * Decides whether the validated ID-token {@code memoryos_identity_provider} claim selects trusted
 * browser JIT admission. Only a strict non-blank String exactly matching a durable allowlist entry
 * qualifies; the composition root pins the issuer separately.
 */
public interface JitAdmissionPolicy {

    boolean allows(Object claim);
}
