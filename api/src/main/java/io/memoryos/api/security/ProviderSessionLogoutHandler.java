package io.memoryos.api.security;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.iam.identity.ProviderSessionTerminator;
import io.memoryos.iam.tenant.TenantAccessResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Runs before the HTTP session is invalidated: ends the identity-provider session recorded at login and remembers
 * whether that succeeded, so {@link SessionLogoutSuccessHandler} only returns a provider logout page when it did not.
 * A provider failure never prevents the local sign-out.
 */
final class ProviderSessionLogoutHandler implements LogoutHandler {

    private static final String ENDED_ATTRIBUTE = ProviderSessionLogoutHandler.class.getName() + ".ENDED";

    private final ProviderSessionTerminator terminator;
    private final AuditTrail audit;
    private final TenantAccessResolver tenants;

    ProviderSessionLogoutHandler(ProviderSessionTerminator terminator, AuditTrail audit, TenantAccessResolver tenants) {
        this.terminator = Objects.requireNonNull(terminator, "terminator must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
    }

    @Override
    public void logout(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @Nullable Authentication authentication
    ) {
        String providerSessionId = ProviderSessionState.read(request);
        boolean ended = false;
        if (providerSessionId != null) {
            try {
                ended = terminator.end(providerSessionId);
            } catch (RuntimeException exception) {
                ended = false;
            }
        }
        request.setAttribute(ENDED_ATTRIBUTE, ended);
        // Onyx has no logout event; a session ending at Keycloak is the other half of the one that began at sign-in.
        if (authentication != null && authentication.getPrincipal() instanceof IdentityContext identity) {
            boolean providerEnded = ended;
            tenants.findActiveTenant(identity.actorId()).ifPresent(tenant -> audit.recordSeparately(
                    AuditRecord.of(AuditAction.LOGOUT, tenant).actor(identity.actorId())
                            .detail("providerSessionEnded", providerEnded).build()));
        }
    }

    static boolean providerSessionEnded(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ENDED_ATTRIBUTE));
    }
}
