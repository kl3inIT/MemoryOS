package io.memoryos.api.mcp;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.mcp.McpException;
import io.memoryos.mcp.McpOAuthProperties;
import io.memoryos.mcp.McpOAuthService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authorization-code callback; the browser returns to administration with an outcome code only. */
@Hidden
@RestController
final class McpOAuthCallbackController {
    private final McpOAuthService oauth;

    McpOAuthCallbackController(McpOAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping(McpOAuthProperties.CALLBACK_PATH)
    void callback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        var pending = McpAuthorizationSessionState.consume(request, single(request, "state"));
        String outcome = "authorization-failed";
        String code = single(request, "code");
        String error = request.getParameter("error");
        String[] issuers = request.getParameterValues("iss");
        if (pending != null && "access_denied".equals(error)) {
            outcome = "authorization-cancelled";
        } else if (pending != null && error == null && code != null && !code.isBlank() && code.length() <= 8192
                && (issuers == null || issuers.length == 1)) {
            try {
                oauth.completeAdministratorAuthorization(new ActorId(pending.actorId()), pending.pending(), code,
                        pending.verifier(), issuers == null ? null : issuers[0]);
                outcome = "connected";
            } catch (McpException failure) {
                outcome = switch (failure.code()) {
                    case "MCP_OAUTH_ISSUER_MISMATCH" -> "issuer-mismatch";
                    case "MCP_CONFLICT" -> "configuration-changed";
                    default -> "authorization-failed";
                };
            } catch (RuntimeException ignored) {
                // Provider bodies and tokens must never enter logs, sessions, or redirect queries.
            }
        }
        response.sendRedirect("/admin/mcp?mcp=" + outcome
                + (pending == null ? "" : "&serverId=" + pending.pending().serverId()));
    }

    private static @Nullable String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 ? values[0] : null;
    }
}
