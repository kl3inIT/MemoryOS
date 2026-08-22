package io.memoryos.api.invitation;

import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationFailureReason;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InvitationController.class)
final class InvitationExceptionHandler {

    @ExceptionHandler(InvitationException.class)
    ResponseEntity<InvitationErrorResponse> invitationFailure(InvitationException exception) {
        return ResponseEntity
                .status(status(exception.reason()))
                .body(new InvitationErrorResponse(exception.reason().name(), message(exception.reason())));
    }

    private static HttpStatus status(InvitationFailureReason reason) {
        return switch (reason) {
            case NOT_OWNER, EMAIL_NOT_VERIFIED, EMAIL_MISMATCH -> HttpStatus.FORBIDDEN;
            case INVALID_EMAIL -> HttpStatus.BAD_REQUEST;
            case INVITATION_NOT_AVAILABLE -> HttpStatus.GONE;
            case INVITATION_CONFLICT, IDENTITY_CONFLICT -> HttpStatus.CONFLICT;
        };
    }

    private static String message(InvitationFailureReason reason) {
        return switch (reason) {
            case NOT_OWNER -> "An active Organization owner is required.";
            case INVALID_EMAIL -> "Enter a valid email address.";
            case INVITATION_CONFLICT -> "An open invitation already exists for this email.";
            case INVITATION_NOT_AVAILABLE -> "This invitation is no longer available.";
            case EMAIL_NOT_VERIFIED -> "Verify the invited email before continuing.";
            case EMAIL_MISMATCH -> "Sign in with the email address that received this invitation.";
            case IDENTITY_CONFLICT -> "This identity already belongs to existing Organization authority.";
        };
    }

    record InvitationErrorResponse(String code, String message) {
    }
}
