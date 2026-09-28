package io.memoryos.api.security;

import io.memoryos.api.invitation.InvitationSessionState;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Authentication itself failed: the provider rejected the exchange, or the token, UserInfo or this application was
 * unreachable while the browser was returning. That is not the same as an identity nobody admitted, which the success
 * handler answers, so it is not reported as one: an administrator restarting the API mid-login once left a member
 * reading "you have not been granted access" (staging, 2026-09-20). Signing in again is what resolves it.
 */
final class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final String SIGN_IN_FAILED_DESTINATION = "/sign-in-failed";
    private static final String INVITATION_FAILURE_DESTINATION = "/invitation?reason=authentication-failed";

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AuthenticationException exception
    ) throws IOException {
        SecurityContextHolder.clearContext();
        boolean invitationFlow = InvitationSessionState.read(request) != null;
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        redirectStrategy.sendRedirect(
                request,
                response,
                invitationFlow ? INVITATION_FAILURE_DESTINATION : SIGN_IN_FAILED_DESTINATION
        );
    }
}
