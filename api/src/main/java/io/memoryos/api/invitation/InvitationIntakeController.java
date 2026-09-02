package io.memoryos.api.invitation;

import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
final class InvitationIntakeController {

    private static final String LANDING_PATH = "/invitation";
    private static final String NOT_AVAILABLE_PATH = LANDING_PATH + "?reason=not-available";
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
        InvitationSessionState.markActivation(request);
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
            new InvitationSessionState(continuation.invitationId(), continuation.tenantId().value())
                    .store(request);
            redirect(response, LANDING_PATH);
        } catch (InvitationException exception) {
            InvitationSessionState.clear(request);
            redirect(response, NOT_AVAILABLE_PATH);
        }
    }

    @GetMapping("/invite/continue")
    void continueInvitation(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        noSecretCaching(response);
        var state = InvitationSessionState.read(request);
        if (state == null) {
            redirect(response, NOT_AVAILABLE_PATH);
            return;
        }
        try {
            invitations.resume(state.invitationId(), state.tenant());
            redirect(response, OAUTH_PATH);
        } catch (InvitationException exception) {
            InvitationSessionState.clear(request);
            redirect(response, NOT_AVAILABLE_PATH);
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
