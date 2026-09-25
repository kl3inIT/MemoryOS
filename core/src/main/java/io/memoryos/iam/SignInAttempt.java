package io.memoryos.iam;

import io.memoryos.shared.TenantId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A browser sign-in the identity provider has authenticated, as the callback read it from the validated ID token.
 *
 * @param identity              the exact {@code (issuer, subject)}; {@code null} when the token could not be read
 * @param assertedLabel         who the provider says this is (e-mail, else subject), naming a refused person in audit
 * @param identityProviderClaim the ID token's {@code memoryos_identity_provider} claim, never a UserInfo value
 * @param invitation            the invitation continuation the browser session carries, if any
 * @param activation            whether the session is a Keycloak activation flow without a continuation
 * @param trust                 the deployment's trusted issuer and the Tenant trusted JIT admission joins
 */
public record SignInAttempt(
        @Nullable ExternalIdentity identity,
        @Nullable String assertedLabel,
        @Nullable Object identityProviderClaim,
        @Nullable String displayName,
        @Nullable String email,
        boolean emailVerified,
        @Nullable Invitation invitation,
        boolean activation,
        JitTrust trust
) {
    public SignInAttempt {
        Objects.requireNonNull(trust, "trust must not be null");
    }

    /** A sign-in whose provider principal or ID token carries no usable identity. */
    public static SignInAttempt unreadable(@Nullable String assertedLabel, JitTrust trust) {
        return new SignInAttempt(null, assertedLabel, null, null, null, false, null, false, trust);
    }

    public record Invitation(UUID invitationId, TenantId tenantId) {
        public Invitation {
            Objects.requireNonNull(invitationId, "invitationId must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }
    }

    /** Only this issuer's ID tokens may select JIT admission, and only into this Tenant. */
    public record JitTrust(String issuer, TenantId tenantId) {
        public JitTrust {
            Objects.requireNonNull(issuer, "issuer must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }
    }
}
