package io.memoryos.iam.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditOutcome;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.ActorProfileRecorder;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.InvitationException;
import io.memoryos.iam.InvitationFailureReason;
import io.memoryos.iam.InvitationService;
import io.memoryos.iam.SignInAttempt;
import io.memoryos.iam.SignInOutcome;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TrustedIdentityAdmission;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The sign-in decision: which path admits whom, which refusals are DENIED or FAILURE, and that admission, the profile
 * and LOGIN share one transaction while a refusal is audited only after it has ended.
 */
class DefaultSignInAdmissionTest {

    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final ExternalIdentity IDENTITY = new ExternalIdentity(ISSUER, "member");

    private final ExternalIdentityResolver identities = mock(ExternalIdentityResolver.class);
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final TrustedIdentityAdmission trusted = mock(TrustedIdentityAdmission.class);
    private final InvitationService invitations = mock(InvitationService.class);
    private final ActorProfileRecorder profiles = mock(ActorProfileRecorder.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final List<String> auditedInside = new ArrayList<>();
    private Set<String> allowlist = Set.of("tasco");
    private DefaultSignInAdmission admission;

    @BeforeEach
    void setup() {
        when(identities.resolve(any())).thenReturn(Optional.empty());
        when(invitations.acceptVerifiedEmail(any())).thenThrow(
                new InvitationException(InvitationFailureReason.NOT_AVAILABLE, "No eligible invitation"));
        doAnswer(call -> auditedInside.add(call.<AuditRecord>getArgument(0).action() + "@" + transactions.inside))
                .when(audit).record(any());
        doAnswer(call -> auditedInside.add(call.<AuditRecord>getArgument(0).action() + "@" + transactions.inside))
                .when(audit).recordSeparately(any());
        admission = new DefaultSignInAdmission(identities, tenants, trusted,
                claim -> claim instanceof String alias && allowlist.contains(alias), invitations, profiles, audit, transactions);
    }

    @Test
    void trustedAliasCannotAdmitFromAnotherIssuer() {
        var attempt = attempt(new ExternalIdentity("https://other.example/realms/memoryos", "member"), "tasco", null, false);
        assertEquals(new SignInOutcome.NotAdmitted(), admission.admit(attempt));
        verifyNoInteractions(trusted, profiles);
        assertRefused(AuditOutcome.DENIED, "NOT_ADMITTED", null);
    }

    @Test
    void trustedClaimCannotAdmitWhenDeploymentHasNotOptedIn() {
        allowlist = Set.of();
        assertEquals(new SignInOutcome.NotAdmitted(), admission.admit(attempt(IDENTITY, "tasco", null, false)));
        verifyNoInteractions(trusted, profiles);
        assertRefused(AuditOutcome.DENIED, "NOT_ADMITTED", null);
    }

    @Test
    void trustedJitAdmitsRecordsTheProfileAndLogsInWithinOneTransaction() {
        var actor = new ActorId(UUID.randomUUID());
        when(trusted.admit(TENANT, IDENTITY)).thenReturn(actor);
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(TENANT));

        assertEquals(new SignInOutcome.Admitted(actor), admission.admit(attempt(IDENTITY, "tasco", null, false)));

        verify(profiles).record(actor, IDENTITY, "Member", "member@example.test", true);
        verify(invitations, never()).acceptVerifiedEmail(any());
        assertEquals(1, transactions.executions);
        assertEquals(List.of("LOGIN@true"), auditedInside);
    }

    @Test
    void anActiveMemberSkipsAdmission() {
        var actor = new ActorId(UUID.randomUUID());
        when(identities.resolve(IDENTITY)).thenReturn(Optional.of(actor));
        when(tenants.hasActiveTenant(actor)).thenReturn(true);
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(TENANT));

        assertEquals(new SignInOutcome.Admitted(actor), admission.admit(attempt(IDENTITY, "tasco", null, false)));
        verifyNoInteractions(trusted, invitations);
        verify(profiles).record(actor, IDENTITY, "Member", "member@example.test", true);
    }

    @Test
    void deniedJitIsRefusedAfterItsTransactionAndOtherIamFailuresPropagate() {
        var known = new ActorId(UUID.randomUUID());
        when(identities.resolve(IDENTITY)).thenReturn(Optional.of(known));
        when(trusted.admit(TENANT, IDENTITY)).thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "inactive"));

        assertEquals(new SignInOutcome.NotAdmitted(), admission.admit(attempt(IDENTITY, "tasco", null, false)));
        assertRefused(AuditOutcome.DENIED, "NOT_ADMITTED", known);
        assertTrue(transactions.rolledBack);
        verifyNoInteractions(profiles);

        var conflict = new IamException(IamFailureReason.GROUP_CONFLICT, "unexpected");
        doThrow(conflict).when(trusted).admit(TENANT, IDENTITY);
        assertSame(conflict, assertThrows(IamException.class, () -> admission.admit(attempt(IDENTITY, "tasco", null, false))));
    }

    @Test
    void anUnusableFollowedInvitationIsAFailureWithItsReason() {
        var continuation = new SignInAttempt.Invitation(UUID.randomUUID(), TENANT);
        when(invitations.accept(any())).thenThrow(new InvitationException(InvitationFailureReason.EMAIL_MISMATCH, "mismatch"));

        assertEquals(new SignInOutcome.InvitationRefused(InvitationFailureReason.EMAIL_MISMATCH),
                admission.admit(attempt(IDENTITY, null, continuation, false)));
        assertRefused(AuditOutcome.FAILURE, "INVITATION_EMAIL_MISMATCH", null);

        auditedInside.clear();
        clearInvocations(audit);
        assertEquals(new SignInOutcome.InvitationRefused(InvitationFailureReason.NOT_AVAILABLE),
                admission.admit(attempt(IDENTITY, null, null, true)));
        assertRefused(AuditOutcome.FAILURE, "INVITATION_NOT_AVAILABLE", null);
    }

    @Test
    void anUnreadableIdentityIsAFailureWithoutAnyLookup() {
        assertEquals(new SignInOutcome.NotAdmitted(),
                admission.admit(SignInAttempt.unreadable("member@example.test", new SignInAttempt.JitTrust(ISSUER, TENANT))));
        verifyNoInteractions(identities, trusted, invitations, profiles);
        assertEquals(0, transactions.executions);
        assertRefused(AuditOutcome.FAILURE, "UNREADABLE_IDENTITY", null);
    }

    @Test
    void aProfileFailureRollsBackTheAdmissionWithIt() {
        var actor = new ActorId(UUID.randomUUID());
        when(trusted.admit(TENANT, IDENTITY)).thenReturn(actor);
        var failure = new IllegalStateException("profile write failed");
        doAnswer(call -> { throw failure; }).when(profiles).record(any(), any(), any(), any(), anyBoolean());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> admission.admit(attempt(IDENTITY, "tasco", null, false))));
        assertTrue(transactions.rolledBack);
        assertTrue(auditedInside.isEmpty());
    }

    private void assertRefused(AuditOutcome outcome, String reason, @Nullable ActorId actor) {
        var event = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).recordSeparately(event.capture());
        assertEquals(AuditAction.LOGIN_FAILURE, event.getValue().action());
        assertEquals(outcome, event.getValue().outcome());
        assertEquals(TENANT, event.getValue().tenant());
        assertEquals(actor, event.getValue().actor());
        assertEquals("member@example.test", event.getValue().actorLabel());
        assertEquals(reason, event.getValue().details().get("reason"));
        assertFalse(auditedInside.contains("LOGIN_FAILURE@true"), "a refusal is audited after its transaction ends");
        verify(audit, never()).record(any());
    }

    private static SignInAttempt attempt(ExternalIdentity identity, @Nullable Object claim,
                                         SignInAttempt.@Nullable Invitation invitation, boolean activation) {
        return new SignInAttempt(identity, "member@example.test", claim, "Member", "member@example.test", true,
                invitation, activation, new SignInAttempt.JitTrust(ISSUER, TENANT));
    }

    /** Runs the callback in-line, noting whether work happened inside it and whether it ended by rolling back. */
    private static final class RecordingTransactions implements TransactionOperations {
        private boolean inside;
        private boolean rolledBack;
        private int executions;

        @Override
        public <T> @Nullable T execute(TransactionCallback<T> action) {
            executions++;
            inside = true;
            TransactionStatus status = new SimpleTransactionStatus();
            try {
                return action.doInTransaction(status);
            } catch (RuntimeException exception) {
                rolledBack = true;
                throw exception;
            } finally {
                inside = false;
            }
        }
    }
}
