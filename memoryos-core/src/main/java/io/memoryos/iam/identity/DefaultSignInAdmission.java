package io.memoryos.iam.identity;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditOutcome;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.ActorProfileRecorder;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.InvitationAcceptance;
import io.memoryos.iam.InvitationException;
import io.memoryos.iam.InvitationService;
import io.memoryos.iam.JitAdmissionPolicy;
import io.memoryos.iam.SignInAdmission;
import io.memoryos.iam.SignInAttempt;
import io.memoryos.iam.SignInOutcome;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TrustedIdentityAdmission;
import io.memoryos.iam.VerifiedEmailInvitationAcceptance;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultSignInAdmission implements SignInAdmission {

    private final ExternalIdentityResolver identities;
    private final TenantAccessResolver tenants;
    private final TrustedIdentityAdmission trustedAdmission;
    private final JitAdmissionPolicy jitPolicy;
    private final InvitationService invitations;
    private final ActorProfileRecorder profiles;
    private final AuditTrail audit;
    private final TransactionOperations transactions;

    @Autowired
    public DefaultSignInAdmission(
            ExternalIdentityResolver identities,
            TenantAccessResolver tenants,
            TrustedIdentityAdmission trustedAdmission,
            JitAdmissionPolicy jitPolicy,
            InvitationService invitations,
            ActorProfileRecorder profiles,
            AuditTrail audit,
            PlatformTransactionManager transactionManager
    ) {
        this(identities, tenants, trustedAdmission, jitPolicy, invitations, profiles, audit,
                new TransactionTemplate(transactionManager));
    }

    DefaultSignInAdmission(
            ExternalIdentityResolver identities,
            TenantAccessResolver tenants,
            TrustedIdentityAdmission trustedAdmission,
            JitAdmissionPolicy jitPolicy,
            InvitationService invitations,
            ActorProfileRecorder profiles,
            AuditTrail audit,
            TransactionOperations transactions
    ) {
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.trustedAdmission = Objects.requireNonNull(trustedAdmission, "trustedAdmission must not be null");
        this.jitPolicy = Objects.requireNonNull(jitPolicy, "jitPolicy must not be null");
        this.invitations = Objects.requireNonNull(invitations, "invitations must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /**
     * One transaction admits, records the profile and the {@code LOGIN}. A refusal is raised inside it and caught only
     * after it has rolled back (a joined inner failure has already marked it rollback-only), so a refused JIT Actor or a
     * half-accepted invitation never commits, and the refusal is audited on its own.
     */
    @Override
    public SignInOutcome admit(SignInAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        ExternalIdentity identity = attempt.identity();
        if (identity == null) {
            refused(attempt, null, "UNREADABLE_IDENTITY", AuditOutcome.FAILURE);
            return new SignInOutcome.NotAdmitted();
        }
        var decision = new Decision();
        try {
            return Objects.requireNonNull(transactions.execute(status -> admitted(attempt, identity, decision)));
        } catch (IamException exception) {
            if (decision.path != Path.JIT || !IamFailureReason.ACCESS_DENIED.code().equals(exception.code())) {
                throw exception;
            }
            refused(attempt, decision.knownActor, "NOT_ADMITTED", AuditOutcome.DENIED);
            return new SignInOutcome.NotAdmitted();
        } catch (InvitationException exception) {
            if (decision.path != Path.INVITATION) {
                throw exception;
            }
            // The acceptance rolled back, so this names only an Actor that existed before the attempt.
            ActorId actor = identities.resolve(identity).orElse(null);
            if (attempt.invitation() != null || attempt.activation()) {
                refused(attempt, actor, "INVITATION_" + exception.reason().name(), AuditOutcome.FAILURE);
                return new SignInOutcome.InvitationRefused(exception.reason());
            }
            refused(attempt, actor, "NOT_ADMITTED", AuditOutcome.DENIED);
            return new SignInOutcome.NotAdmitted();
        }
    }

    private SignInOutcome admitted(SignInAttempt attempt, ExternalIdentity identity, Decision decision) {
        ActorId actorId = identities.resolve(identity).orElse(null);
        decision.knownActor = actorId;
        if (actorId == null || !tenants.hasActiveTenant(actorId)) {
            if (attempt.trust().issuer().equals(identity.issuer()) && jitPolicy.allows(attempt.identityProviderClaim())) {
                decision.path = Path.JIT;
                actorId = trustedAdmission.admit(attempt.trust().tenantId(), identity);
            } else {
                decision.path = Path.INVITATION;
                actorId = acceptInvitation(attempt, identity);
            }
            decision.path = Path.ADMITTED;
        }
        profiles.record(actorId, identity, attempt.displayName(), attempt.email(), attempt.emailVerified());
        ActorId signedIn = actorId;
        tenants.findActiveTenant(signedIn).ifPresent(tenant ->
                audit.record(AuditRecord.of(AuditAction.LOGIN, tenant).actor(signedIn).build()));
        return new SignInOutcome.Admitted(signedIn);
    }

    private ActorId acceptInvitation(SignInAttempt attempt, ExternalIdentity identity) {
        SignInAttempt.Invitation continuation = attempt.invitation();
        if (continuation != null) {
            return invitations.accept(new InvitationAcceptance(continuation.invitationId(), continuation.tenantId(),
                    identity, attempt.email(), attempt.emailVerified()));
        }
        return invitations.acceptVerifiedEmail(
                new VerifiedEmailInvitationAcceptance(identity, attempt.email(), attempt.emailVerified()));
    }

    /**
     * A sign-in that authenticated at the provider but was not let in. Onyx records the same as
     * {@code auth.login_failure}: refused (not admitted) is {@code DENIED}, an invitation that could not be used is
     * {@code FAILURE}. The person is named by what their provider asserted, since they may have no profile here.
     */
    private void refused(SignInAttempt attempt, @Nullable ActorId actor, String reason, AuditOutcome outcome) {
        TenantId tenant = attempt.trust().tenantId();
        audit.recordSeparately(AuditRecord.of(AuditAction.LOGIN_FAILURE, tenant).outcome(outcome)
                .actor(actor, attempt.assertedLabel())
                .detail("reason", reason).build());
    }

    private enum Path { MEMBER, JIT, INVITATION, ADMITTED }

    /** What the transaction had decided when a refusal escaped it. */
    private static final class Decision {
        private Path path = Path.MEMBER;
        private @Nullable ActorId knownActor;
    }
}
