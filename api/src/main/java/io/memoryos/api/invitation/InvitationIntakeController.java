package io.memoryos.api.invitation;

import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationService;
import io.memoryos.organization.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
final class InvitationIntakeController {

    private static final String LANDING_PATH = "/invitation";
    private static final String OAUTH_PATH = "/oauth2/authorization/memoryos";
    //noinspection HttpHeaderName
    private static final String REFERRER_POLICY = "Referrer-Policy";

    private final InvitationService invitations;

    InvitationIntakeController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping("/invite/activate")
    void activate(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        noSecretCaching(response);
        var session = request.getSession(true);
        session.removeAttribute(InvitationSessionState.ATTRIBUTE);
        session.setAttribute(InvitationSessionState.ACTIVATION_ATTRIBUTE, Boolean.TRUE);
        redirect(response, OAUTH_PATH);
    }

    @GetMapping("/invite/{secret}")
    void intake(
            @PathVariable String secret,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        noSecretCaching(response);
        try {
            var continuation = invitations.intake(secret);
            var state = new InvitationSessionState(
                    continuation.invitationId(),
                    continuation.organizationId().value(),
                    continuation.expiresAt()
            );
            request.getSession(true).setAttribute(InvitationSessionState.ATTRIBUTE, state);
            redirect(response, LANDING_PATH);
        } catch (InvitationException exception) {
            var session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(InvitationSessionState.ATTRIBUTE);
            }
            redirect(response, LANDING_PATH + "?reason=not-available");
        }
    }

    @GetMapping("/invite/continue")
    void continueInvitation(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        noSecretCaching(response);
        var session = request.getSession(false);
        var state = session == null
                ? null
                : session.getAttribute(InvitationSessionState.ATTRIBUTE);
        if (!(state instanceof InvitationSessionState(
                var invitationId,
                var organizationId,
                var expiresAt
        ))
                || !expiresAt.isAfter(Instant.now())) {
            redirect(response, LANDING_PATH + "?reason=not-available");
            return;
        }
        try {
            invitations.resume(invitationId, new OrganizationId(organizationId));
            redirect(response, OAUTH_PATH);
        } catch (InvitationException exception) {
            session.removeAttribute(InvitationSessionState.ATTRIBUTE);
            redirect(response, LANDING_PATH + "?reason=not-available");
        }
    }


    private static void noSecretCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(REFERRER_POLICY, "no-referrer");
    }

    private static void redirect(HttpServletResponse response, String location) {
        response.setStatus(HttpStatus.SEE_OTHER.value());
        response.setHeader(HttpHeaders.LOCATION, location);
    }
}
