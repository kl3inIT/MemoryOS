package io.memoryos.iam.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityRegistrar;
import io.memoryos.iam.GroupProvisioner;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.InvitationAcceptance;
import io.memoryos.iam.InvitationContinuation;
import io.memoryos.iam.InvitationDelivery;
import io.memoryos.iam.InvitationException;
import io.memoryos.iam.InvitationFailureReason;
import io.memoryos.iam.InvitationPage;
import io.memoryos.iam.InvitationQuery;
import io.memoryos.iam.InvitationService;
import io.memoryos.iam.InvitationTarget;
import io.memoryos.iam.InvitationView;
import io.memoryos.iam.IssuedInvitation;
import io.memoryos.iam.KeycloakRecipientProvisioner;
import io.memoryos.iam.KeycloakRecipientProvisioning;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipProvisioner;
import io.memoryos.iam.VerifiedEmailInvitationAcceptance;
import io.memoryos.iam.persistence.ActorEntity;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.InvitationEntity;
import io.memoryos.iam.persistence.InvitationQueryRepository;
import io.memoryos.iam.persistence.JpaInvitationRepository;

@Service
public class DefaultInvitationService implements InvitationService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int SECRET_BYTES = 32;

    private final JpaInvitationRepository invitations;
    private final InvitationQueryRepository invitationQueries;
    private final ExternalIdentityRegistrar identityRegistrar;
    private final TenantMembershipProvisioner membershipProvisioner;
    private final GroupProvisioner groupProvisioner;
    private final KeycloakRecipientProvisioner keycloakProvisioner;
    private final IamAuthorization authorization;
    private final IamLockRepository locks;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Duration timeToLive;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public DefaultInvitationService(
            JpaInvitationRepository invitations,
            InvitationQueryRepository invitationQueries,
            ExternalIdentityRegistrar identityRegistrar,
            TenantMembershipProvisioner membershipProvisioner,
            GroupProvisioner groupProvisioner,
            KeycloakRecipientProvisioner keycloakProvisioner,
            IamAuthorization authorization,
            IamLockRepository locks,
            PlatformTransactionManager transactionManager,
            @Value("${memoryos.invitation.time-to-live:PT72H}") Duration timeToLive
    ) {
        this(
                invitations,
                invitationQueries,
                identityRegistrar,
                membershipProvisioner,
                groupProvisioner,
                keycloakProvisioner,
                authorization,
                locks,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager,
                        "transactionManager must not be null"
                )),
                Clock.systemUTC(),
                timeToLive
        );
    }

    DefaultInvitationService(
            JpaInvitationRepository invitations,
            InvitationQueryRepository invitationQueries,
            ExternalIdentityRegistrar identityRegistrar,
            TenantMembershipProvisioner membershipProvisioner,
            GroupProvisioner groupProvisioner,
            KeycloakRecipientProvisioner keycloakProvisioner,
            IamAuthorization authorization,
            IamLockRepository locks,
            TransactionOperations transactions,
            Clock clock,
            Duration timeToLive
    ) {
        this.invitations = Objects.requireNonNull(invitations, "invitations must not be null");
        this.invitationQueries = Objects.requireNonNull(invitationQueries, "invitationQueries must not be null");
        this.identityRegistrar = Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null");
        this.membershipProvisioner = Objects.requireNonNull(
                membershipProvisioner,
                "membershipProvisioner must not be null"
        );
        this.groupProvisioner = Objects.requireNonNull(groupProvisioner, "groupProvisioner must not be null");
        this.keycloakProvisioner = Objects.requireNonNull(
                keycloakProvisioner,
                "keycloakProvisioner must not be null"
        );
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeToLive = requireTimeToLive(timeToLive);
    }

    @Override
    public IssuedInvitation issue(ActorId administrator, String email) {
        IamAccess preflightAccess = requireAdministration(administrator, false);
        String normalizedEmail = normalizeEmail(email);
        Instant now = clock.instant();
        boolean openInvitationExists = Boolean.TRUE.equals(transactions.execute(
                _ -> !invitations.findPendingByEmail(normalizedEmail, now).isEmpty()
        ));
        if (openInvitationExists) {
            throw new InvitationException(
                    InvitationFailureReason.CONFLICT,
                    "an open invitation already exists for this email"
            );
        }
        UUID invitationId = UUID.randomUUID();
        String plaintextSecret = newSecret();
        String secretDigest = digest(plaintextSecret);
        Instant expiresAt = now.plus(timeToLive);

        KeycloakRecipientProvisioning provisioning = keycloakProvisioner.provision(normalizedEmail, expiresAt);
        InvitationView invitation;
        try {
            invitation = Objects.requireNonNull(transactions.execute(_ -> {
                TenantId tenantId = requireAdministration(administrator, true).tenantId();
                if (!tenantId.equals(preflightAccess.tenantId())) {
                    throw notOwner();
                }
                invitations.expirePending(tenantId, normalizedEmail, now);
                return JpaInvitationRepository.view(invitations.create(
                        invitationId,
                        tenantId,
                        normalizedEmail,
                        secretDigest,
                        administrator,
                        now,
                        expiresAt
                ));
            }), "invitation transaction returned null");
        } catch (DataIntegrityViolationException exception) {
            throw new InvitationException(
                    InvitationFailureReason.CONFLICT,
                    "an open invitation already exists for this email",
                    exception
            );
        }

        return new IssuedInvitation(invitation, plaintextSecret, delivery(provisioning));
    }

    @Override
    @Transactional
    public InvitationPage list(ActorId administrator, InvitationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        TenantId tenantId = requireAdministrationShared(administrator).tenantId();
        invitations.expirePending(tenantId, clock.instant());
        long totalItems = invitationQueries.count(tenantId, query);
        return new InvitationPage(
                invitationQueries.findPage(tenantId, query),
                query.page(),
                query.size(),
                totalItems,
                Math.ceilDiv(totalItems, query.size())
        );
    }

    @Override
    @Transactional
    public IssuedInvitation rotate(ActorId administrator, UUID invitationId) {
        InvitationEntity invitation = pendingAdministrativeInvitation(administrator, invitationId);
        Instant now = clock.instant();
        String plaintextSecret = newSecret();
        Instant expiresAt = now.plus(timeToLive);
        try {
            invitation.rotate(digest(plaintextSecret), now, expiresAt);
            invitations.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new InvitationException(InvitationFailureReason.CONFLICT, "could not rotate invitation", exception);
        }
        return new IssuedInvitation(
                JpaInvitationRepository.view(invitation),
                plaintextSecret,
                InvitationDelivery.RECOVERY_LINK_ONLY
        );
    }

    @Override
    @Transactional
    public void revoke(ActorId administrator, UUID invitationId) {
        InvitationEntity invitation = pendingAdministrativeInvitation(administrator, invitationId);
        invitation.revoke(invitations.requireActor(administrator), clock.instant());
        invitations.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationContinuation intake(String plaintextSecret) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw notAvailable();
        }
        InvitationEntity invitation = invitations.findByDigest(digest(plaintextSecret))
                .orElseThrow(DefaultInvitationService::notAvailable);
        requireAvailable(invitation);
        return continuation(invitation, activeTarget(invitation));
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationContinuation resume(UUID invitationId, TenantId tenantId) {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        InvitationEntity invitation = invitations.find(tenantId, invitationId)
                .orElseThrow(DefaultInvitationService::notAvailable);
        requireAvailable(invitation);
        return continuation(invitation, activeTarget(invitation));
    }

    @Override
    @Transactional
    public ActorId accept(InvitationAcceptance acceptance) {
        Objects.requireNonNull(acceptance, "acceptance must not be null");
        Objects.requireNonNull(acceptance.invitationId(), "invitationId must not be null");
        Objects.requireNonNull(acceptance.tenantId(), "tenantId must not be null");
        Objects.requireNonNull(acceptance.externalIdentity(), "externalIdentity must not be null");
        requireVerifiedEmail(acceptance.emailVerified());

        lockAcceptanceTenant(acceptance.tenantId());
        InvitationEntity invitation = lock(acceptance.tenantId(), acceptance.invitationId());
        requireAvailable(invitation);
        return acceptLocked(
                invitation,
                acceptance.externalIdentity(),
                normalizeEmail(acceptance.email())
        );
    }

    @Override
    @Transactional
    public ActorId acceptVerifiedEmail(VerifiedEmailInvitationAcceptance acceptance) {
        Objects.requireNonNull(acceptance, "acceptance must not be null");
        Objects.requireNonNull(acceptance.externalIdentity(), "externalIdentity must not be null");
        requireVerifiedEmail(acceptance.emailVerified());

        String normalizedEmail = normalizeEmail(acceptance.email());
        Instant now = clock.instant();
        List<InvitationEntity> candidates = invitations.findPendingByEmail(normalizedEmail, now);
        if (candidates.size() != 1) {
            throw notAvailable();
        }
        InvitationEntity candidate = candidates.getFirst();
        TenantId tenantId = new TenantId(candidate.getTenant().getId());
        UUID invitationId = candidate.getId();

        lockAcceptanceTenant(tenantId);
        InvitationEntity invitation = lock(tenantId, invitationId);
        requireAvailable(invitation);
        if (!invitation.getNormalizedEmail().equals(normalizedEmail)) {
            throw notAvailable();
        }
        return acceptLocked(invitation, acceptance.externalIdentity(), normalizedEmail);
    }

    private ActorId acceptLocked(
            InvitationEntity invitation,
            ExternalIdentity externalIdentity,
            String normalizedEmail
    ) {
        InvitationTarget target = activeTarget(invitation);
        if (!invitation.getNormalizedEmail().equals(normalizedEmail)) {
            throw new InvitationException(
                    InvitationFailureReason.EMAIL_MISMATCH,
                    "authenticated email does not match invitation"
            );
        }

        try {
            ActorId actorId = identityRegistrar.resolveOrCreateLocked(externalIdentity);
            if (membershipProvisioner.hasAnyMembership(actorId)) {
                throw new InvitationException(
                        InvitationFailureReason.IDENTITY_CONFLICT,
                        "identity already has Tenant authority"
                );
            }
            membershipProvisioner.grantMember(target.tenantId(), actorId);
            groupProvisioner.addToBasicGroup(target.tenantId(), actorId);
            ActorEntity actor = invitations.requireActor(actorId);
            invitation.accept(actor, clock.instant());
            invitations.flush();
            return actorId;
        } catch (DataIntegrityViolationException exception) {
            throw new InvitationException(
                    InvitationFailureReason.IDENTITY_CONFLICT,
                    "invitation identity or membership conflicts with existing authority",
                    exception
            );
        }
    }

    private InvitationEntity pendingAdministrativeInvitation(ActorId administrator, UUID invitationId) {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        TenantId tenantId = requireAdministration(administrator, true).tenantId();
        InvitationEntity invitation = lock(tenantId, invitationId);
        requireAvailable(invitation);
        return invitation;
    }

    private InvitationEntity lock(TenantId tenantId, UUID invitationId) {
        return invitations.findLocked(tenantId, invitationId)
                .orElseThrow(DefaultInvitationService::notAvailable);
    }

    private InvitationTarget activeTarget(InvitationEntity invitation) {
        TenantId tenantId = new TenantId(invitation.getTenant().getId());
        return membershipProvisioner.findActiveInvitationTarget(tenantId)
                .orElseThrow(DefaultInvitationService::notAvailable);
    }

    private void lockAcceptanceTenant(TenantId tenantId) {
        try {
            locks.lockTenant(tenantId);
        } catch (IamException exception) {
            throw notAvailable();
        }
    }

    private IamAccess requireAdministrationShared(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        try {
            return authorization.lockAndRequire(actorId, IamCapability.USERS_MANAGE, false);
        } catch (IamException exception) {
            throw notOwner(exception);
        }
    }

    private IamAccess requireAdministration(ActorId actorId, boolean lock) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        try {
            return lock
                    ? authorization.lockAndRequireExclusive(actorId, IamCapability.USERS_MANAGE)
                    : authorization.require(actorId, IamCapability.USERS_MANAGE, false);
        } catch (IamException exception) {
            throw notOwner(exception);
        }
    }

    private static InvitationContinuation continuation(
            InvitationEntity invitation,
            InvitationTarget target
    ) {
        return new InvitationContinuation(
                invitation.getId(),
                target.tenantId(),
                target.tenantDisplayName(),
                invitation.getExpiresAt()
        );
    }

    private static InvitationDelivery delivery(KeycloakRecipientProvisioning provisioning) {
        return switch (provisioning) {
            case ACTIVATION_EMAIL_SENT -> InvitationDelivery.ACTIVATION_EMAIL_SENT;
            case EXISTING_VERIFIED -> InvitationDelivery.EXISTING_ACCOUNT;
        };
    }

    private static void requireVerifiedEmail(boolean emailVerified) {
        if (!emailVerified) {
            throw new InvitationException(
                    InvitationFailureReason.EMAIL_NOT_VERIFIED,
                    "invitation email is not verified"
            );
        }
    }

    private static Duration requireTimeToLive(Duration value) {
        Objects.requireNonNull(value, "memoryos.invitation.time-to-live must not be null");
        if (value.compareTo(Duration.ofMinutes(5)) < 0
                || value.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                    "memoryos.invitation.time-to-live must be between 5 minutes and 30 days"
            );
        }
        return value;
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            throw new InvitationException(InvitationFailureReason.INVALID_EMAIL, "email is required");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw new InvitationException(InvitationFailureReason.INVALID_EMAIL, "email is invalid");
        }
        return normalized;
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String plaintextSecret) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(plaintextSecret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireAvailable(InvitationEntity invitation) {
        if (!invitation.isAvailableAt(clock.instant())) {
            throw notAvailable();
        }
    }

    private static InvitationException notOwner() {
        return new InvitationException(InvitationFailureReason.NOT_OWNER, "USERS_MANAGE capability is required");
    }

    private static InvitationException notOwner(IamException cause) {
        return new InvitationException(
                InvitationFailureReason.NOT_OWNER,
                "USERS_MANAGE capability is required",
                cause
        );
    }

    private static InvitationException notAvailable() {
        return new InvitationException(InvitationFailureReason.NOT_AVAILABLE, "invitation is not available");
    }
}
