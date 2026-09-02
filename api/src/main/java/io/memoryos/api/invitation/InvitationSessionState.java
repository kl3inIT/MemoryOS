package io.memoryos.api.invitation;

import io.memoryos.tenant.TenantId;
import jakarta.servlet.http.HttpServletRequest;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The invitation continuation carried in the browser session between the capability link, the landing
 * page, and the OIDC callback. Availability and expiry are re-checked by the invitation capability.
 */
public record InvitationSessionState(UUID invitationId, UUID tenantId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 2L;

    private static final String ATTRIBUTE = InvitationSessionState.class.getName();
    private static final String ACTIVATION_ATTRIBUTE = ATTRIBUTE + ".activation";

    public InvitationSessionState {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    public TenantId tenant() {
        return new TenantId(tenantId);
    }

    /** Stores this continuation, creating the session when needed. */
    public void store(HttpServletRequest request) {
        request.getSession(true).setAttribute(ATTRIBUTE, this);
    }

    /** Marks the session as a Keycloak activation flow that carries no continuation. */
    public static void markActivation(HttpServletRequest request) {
        var session = request.getSession(true);
        session.removeAttribute(ATTRIBUTE);
        session.setAttribute(ACTIVATION_ATTRIBUTE, Boolean.TRUE);
    }

    public static @Nullable InvitationSessionState read(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(ATTRIBUTE) instanceof InvitationSessionState state
                ? state
                : null;
    }

    public static boolean isActivation(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(ACTIVATION_ATTRIBUTE));
    }

    /** Removes the continuation and activation markers from an existing session, if any. */
    public static void clear(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
            session.removeAttribute(ACTIVATION_ATTRIBUTE);
        }
    }
}
