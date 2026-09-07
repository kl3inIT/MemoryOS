package io.memoryos.api.source;

import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.identity.ActorId;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
final class GoogleDriveOAuthCallbackController {
    private final GoogleDriveAuthorizationService authorizations;
    private final GoogleDriveAccountClient accounts;
    GoogleDriveOAuthCallbackController(GoogleDriveAuthorizationService authorizations, GoogleDriveAccountClient accounts) {
        this.authorizations = authorizations; this.accounts = accounts;
    }

    @GetMapping("/login/oauth2/code/google-drive")
    void callback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        var state = GoogleDriveAuthorizationSessionState.consume(request, single(request, "state"));
        var credentialId = state == null ? null : state.credentialId();
        String outcome = "authorization-failed";
        String code = single(request, "code");
        if (state != null && code != null && !code.isBlank() && code.length() <= 8192 && request.getParameter("error") == null) {
            try (var client = authorizations.oauthClient(new ActorId(state.actorId()), state.preparation());
                    var grant = accounts.exchange(code, state, client)) {
                credentialId = authorizations.complete(new ActorId(state.actorId()), state.preparation(), grant).value();
                outcome = "connected";
            } catch (RuntimeException ignored) {
                // Provider bodies and tokens must never enter logs, sessions, or redirect queries.
            }
        }
        response.sendRedirect("/admin/sources/new/google-drive?googleDrive=" + outcome
                + (credentialId == null ? "" : "&credentialId=" + credentialId));
    }

    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 ? values[0] : null;
    }
}
