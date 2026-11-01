package io.memoryos.iam;

/**
 * Decides whether an authenticated browser identity may sign in: an existing active member; otherwise trusted JIT
 * admission when the attempt's issuer is the trusted one and its ID-token provider claim is allowlisted; otherwise
 * invitation acceptance (the carried continuation, else a unique pending invitation for the verified e-mail).
 * Admission and the profile observation commit together or not at all. A refusal is recorded as a separate
 * {@code LOGIN_FAILURE} audit event ({@code DENIED} when not admitted, {@code FAILURE} for an unreadable identity or an
 * unusable invitation); an admitted member's {@code LOGIN} commits with the admission.
 */
public interface SignInAdmission {

    SignInOutcome admit(SignInAttempt attempt);
}
