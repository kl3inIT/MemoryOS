package io.memoryos.invitation;

public enum InvitationFailureReason {
    NOT_OWNER,
    INVALID_EMAIL,
    INVITATION_CONFLICT,
    INVITATION_NOT_AVAILABLE,
    EMAIL_NOT_VERIFIED,
    EMAIL_MISMATCH,
    IDENTITY_CONFLICT
}
