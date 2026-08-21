package io.memoryos.api.security;
import io.memoryos.api.invitation.InvitationSessionState;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

final class BrowserAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final String ACCESS_NOT_PROVISIONED_DESTINATION = "/access-not-provisioned";
    private static final String INVITATION_FAILURE_DESTINATION = "/invitation?reason=authentication-failed";

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AuthenticationException exception
    ) throws IOException {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        boolean invitationFlow = session != null
                && session.getAttribute(InvitationSessionState.ATTRIBUTE) instanceof InvitationSessionState;
        if (session != null) {
            session.invalidate();
        }
        redirectStrategy.sendRedirect(
                request,
                response,
                invitationFlow ? INVITATION_FAILURE_DESTINATION : ACCESS_NOT_PROVISIONED_DESTINATION
        );
    }
}
