package io.memoryos.api.invitation;

import io.memoryos.identity.IdentityContext;
import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationFailureReason;
import io.memoryos.invitation.InvitationService;
import io.memoryos.invitation.InvitationStatus;
import io.memoryos.invitation.InvitationView;
import io.memoryos.invitation.IssuedInvitation;
import io.memoryos.organization.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/invitations")
final class InvitationController {

    static final String BROWSER_REQUEST_HEADER = "X-MemoryOS-CSRF";
    static final String BROWSER_REQUEST_VALUE = "1";

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping
    List<InvitationResponse> list(@AuthenticationPrincipal IdentityContext identityContext) {
        return invitations.list(identityContext.actorId()).stream()
                .map(InvitationController::response)
                .toList();
    }

    @ResponseStatus(HttpStatus.CREATED)

    @PostMapping
    IssuedInvitationResponse create(
            @AuthenticationPrincipal IdentityContext identityContext,
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @RequestBody CreateInvitationRequest request
    ) {
        requireBrowserMutation(browserRequest);
        return issued(invitations.issue(identityContext.actorId(), request.email()));
    }

    @PostMapping("/{invitationId}/rotate")
    IssuedInvitationResponse rotate(
            @AuthenticationPrincipal IdentityContext identityContext,
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @PathVariable UUID invitationId
    ) {
        requireBrowserMutation(browserRequest);
        return issued(invitations.rotate(identityContext.actorId(), invitationId));
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{invitationId}")
    void revoke(
            @AuthenticationPrincipal IdentityContext identityContext,
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @PathVariable UUID invitationId
    ) {
        requireBrowserMutation(browserRequest);
        invitations.revoke(identityContext.actorId(), invitationId);
    }

    @GetMapping("/current")
    CurrentInvitationResponse current(HttpServletRequest request) {
        var session = request.getSession(false);
        var state = session == null
                ? null
                : session.getAttribute(InvitationSessionState.ATTRIBUTE);
        if (!(state instanceof InvitationSessionState invitationState)) {
            throw unavailable();
        }
        try {
            var continuation = invitations.resume(
                    invitationState.invitationId(),
                    new OrganizationId(invitationState.organizationId())
            );
            return new CurrentInvitationResponse(
                    continuation.organizationDisplayName(),
                    continuation.expiresAt(),
                    "/invite/continue"
            );
        } catch (InvitationException exception) {
            session.removeAttribute(InvitationSessionState.ATTRIBUTE);
            throw exception;
        }
    }

    private static IssuedInvitationResponse issued(
            IssuedInvitation invitation
    ) {
        return new IssuedInvitationResponse(
                response(invitation.invitation()),
                "/invite/" + invitation.plaintextSecret()
        );
    }

    private static InvitationResponse response(InvitationView invitation) {
        return new InvitationResponse(
                invitation.id(),
                invitation.email(),
                invitation.status(),
                invitation.createdAt(),
                invitation.expiresAt(),
                invitation.acceptedActorId() == null ? null : invitation.acceptedActorId().value(),
                invitation.acceptedAt(),
                invitation.revokedAt()
        );
    }

    private static void requireBrowserMutation(String browserRequest) {
        if (!BROWSER_REQUEST_VALUE.equals(browserRequest)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "same-origin browser request required");
        }
    }

    private static InvitationException unavailable() {
        return new InvitationException(
                InvitationFailureReason.INVITATION_NOT_AVAILABLE,
                "invitation continuation is not available"
        );
    }

    record CreateInvitationRequest(String email) {
    }

    record InvitationResponse(
            UUID id,
            String email,
            InvitationStatus status,
            Instant createdAt,
            Instant expiresAt,
            UUID acceptedActorId,
            Instant acceptedAt,
            Instant revokedAt
    ) {
    }

    record IssuedInvitationResponse(InvitationResponse invitation, String invitationUrl) {
    }

    record CurrentInvitationResponse(
            String organizationDisplayName,
            Instant expiresAt,
            String continueUrl
    ) {
    }
}
