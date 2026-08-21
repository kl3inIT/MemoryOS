package io.memoryos.api.invitation;

import io.memoryos.invitation.OrganizationInvitationException;
import io.memoryos.invitation.OrganizationInvitationService;
import io.memoryos.organization.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
final class InvitationIntakeController {

    private static final int NONCE_BYTES = 24;
    private static final String LANDING_PATH = "/invitation";
    private static final String OAUTH_PATH = "/oauth2/authorization/memoryos";

    private final OrganizationInvitationService invitations;
    private final SecureRandom secureRandom = new SecureRandom();

    InvitationIntakeController(OrganizationInvitationService invitations) {
        this.invitations = invitations;
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
                    continuation.expiresAt(),
                    nonce()
            );
            request.getSession(true).setAttribute(InvitationSessionState.ATTRIBUTE, state);
            redirect(response, LANDING_PATH);
        } catch (OrganizationInvitationException exception) {
            redirect(response, LANDING_PATH + "?reason=not-available");
        }
    }

    @GetMapping("/invite/continue")
    void continueInvitation(
            @RequestParam String nonce,
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
                var expiresAt,
                var expectedNonce
        ))
                || !expectedNonce.equals(nonce)
                || !expiresAt.isAfter(Instant.now())) {
            redirect(response, LANDING_PATH + "?reason=not-available");
            return;
        }
        try {
            invitations.resume(invitationId, new OrganizationId(organizationId));
            redirect(response, OAUTH_PATH);
        } catch (OrganizationInvitationException exception) {
            session.removeAttribute(InvitationSessionState.ATTRIBUTE);
            redirect(response, LANDING_PATH + "?reason=not-available");
        }
    }

    private String nonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void noSecretCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(InvitationHttpHeaders.REFERRER_POLICY, "no-referrer");
    }

    private static void redirect(HttpServletResponse response, String location) {
        response.setStatus(HttpStatus.SEE_OTHER.value());
        response.setHeader(HttpHeaders.LOCATION, location);
    }
}
