package io.memoryos.invitation.application;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.KeycloakInvitationProvisioner;
import io.memoryos.identity.KeycloakRecipientProvisioning;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.invitation.InvitationAcceptance;
import io.memoryos.invitation.InvitationContinuation;
import io.memoryos.invitation.InvitationDelivery;
import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationFailureReason;
import io.memoryos.invitation.InvitationPage;
import io.memoryos.invitation.InvitationQuery;
import io.memoryos.invitation.InvitationService;
import io.memoryos.invitation.InvitationStatus;
import io.memoryos.invitation.InvitationView;
import io.memoryos.invitation.IssuedInvitation;
import io.memoryos.invitation.VerifiedEmailInvitationAcceptance;
import io.memoryos.invitation.persistence.InvitationRow;
import io.memoryos.invitation.persistence.JdbcInvitationRepository;
import io.memoryos.organization.InvitationAuthority;
import io.memoryos.organization.InvitationTarget;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.OrganizationMembershipProvisioner;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultInvitationService implements InvitationService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int SECRET_BYTES = 32;

    private final JdbcInvitationRepository invitationRepository;
    private final ExternalIdentityRegistrar identityRegistrar;
    private final OrganizationMembershipProvisioner membershipProvisioner;
    private final KeycloakInvitationProvisioner keycloakProvisioner;
    private final Clock clock;
    private final Duration timeToLive;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public DefaultInvitationService(
            JdbcInvitationRepository invitationRepository,
            ExternalIdentityRegistrar identityRegistrar,
            OrganizationMembershipProvisioner membershipProvisioner,
            KeycloakInvitationProvisioner keycloakProvisioner,
            @Value("${memoryos.invitation.time-to-live:PT72H}") Duration timeToLive
    ) {
        this(
                invitationRepository,
                identityRegistrar,
                membershipProvisioner,
                keycloakProvisioner,
                Clock.systemUTC(),
                timeToLive
        );
    }

    DefaultInvitationService(
            JdbcInvitationRepository invitationRepository,
            ExternalIdentityRegistrar identityRegistrar,
            OrganizationMembershipProvisioner membershipProvisioner,
            KeycloakInvitationProvisioner keycloakProvisioner,
            Clock clock,
            Duration timeToLive
    ) {
        this.invitationRepository = Objects.requireNonNull(
                invitationRepository,
                "invitationRepository must not be null"
        );
        this.identityRegistrar = Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null");
        this.membershipProvisioner = Objects.requireNonNull(
                membershipProvisioner,
                "membershipProvisioner must not be null"
        );
        this.keycloakProvisioner = Objects.requireNonNull(
                keycloakProvisioner,
                "keycloakProvisioner must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeToLive = requireTimeToLive(timeToLive);
    }

    @Override
    @Transactional
    public IssuedInvitation issue(ActorId ownerActorId, String email) {
        InvitationAuthority owner = ownerContext(ownerActorId);
        String normalizedEmail = normalizeEmail(email);
        Instant now = clock.instant();
        invitationRepository.expirePending(owner.organizationId(), normalizedEmail, now);

        UUID invitationId = UUID.randomUUID();
        String plaintextSecret = newSecret();
        Instant expiresAt = now.plus(timeToLive);
        try {
            requireOne(invitationRepository.insert(
                    invitationId,
                    owner.organizationId(),
                    normalizedEmail,
                    digest(plaintextSecret),
                    ownerActorId,
                    now,
                    expiresAt
            ), "create invitation");
        } catch (DataIntegrityViolationException exception) {
            throw new InvitationException(InvitationFailureReason.INVITATION_CONFLICT, "an open invitation already exists for this email", exception);
        }

        KeycloakRecipientProvisioning provisioning = keycloakProvisioner.provision(
                normalizedEmail,
                expiresAt
        );
        return new IssuedInvitation(
                new InvitationView(
                        invitationId,
                        owner.organizationId(),
                        normalizedEmail,
                        InvitationStatus.PENDING,
                        now,
                        expiresAt,
                        null,
                        null,
                        null
                ),
                plaintextSecret,
                delivery(provisioning)
        );
    }

    @Override
    @Transactional
    public InvitationPage list(ActorId ownerActorId, InvitationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        InvitationAuthority owner = ownerContext(ownerActorId);
        invitationRepository.expirePending(owner.organizationId(), clock.instant());
        long totalItems = invitationRepository.count(owner.organizationId(), query);
        var items = invitationRepository.findPage(owner.organizationId(), query).stream()
                .map(DefaultInvitationService::view)
                .toList();
        return new InvitationPage(
                items,
                query.page(),
                query.size(),
                totalItems,
                Math.ceilDiv(totalItems, query.size())
        );
    }

    @Override
    @Transactional
    public IssuedInvitation rotate(ActorId ownerActorId, UUID invitationId) {
        InvitationRow invitation = pendingOwnedInvitation(ownerActorId, invitationId);
        Instant now = clock.instant();
        String plaintextSecret = newSecret();
        Instant expiresAt = now.plus(timeToLive);
        try {
            requireOne(
                    invitationRepository.rotate(invitation, digest(plaintextSecret), expiresAt, now),
                    "rotate invitation"
            );
        } catch (DataIntegrityViolationException exception) {
            throw new InvitationException(InvitationFailureReason.INVITATION_CONFLICT, "could not rotate invitation", exception);
        }

        return new IssuedInvitation(
                view(invitation, expiresAt),
                plaintextSecret,
                InvitationDelivery.RECOVERY_LINK_ONLY
        );
    }

    @Override
    @Transactional
    public void revoke(ActorId ownerActorId, UUID invitationId) {
        InvitationRow invitation = pendingOwnedInvitation(ownerActorId, invitationId);
        requireOne(
                invitationRepository.revoke(invitation, ownerActorId, clock.instant()),
                "revoke invitation"
        );
    }

    @Override
    @Transactional
    public InvitationContinuation intake(String plaintextSecret) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw notAvailable();
        }
        InvitationRow invitation = invitationRepository.findLockedByDigest(digest(plaintextSecret))
                .orElseThrow(DefaultInvitationService::notAvailable);
        requireAvailable(invitation);
        return continuation(invitation, activeTarget(invitation));
    }

    @Override
    @Transactional
    public InvitationContinuation resume(UUID invitationId, OrganizationId organizationId) {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        InvitationRow invitation = lock(organizationId, invitationId);
        requireAvailable(invitation);
        return continuation(invitation, activeTarget(invitation));
    }

    @Override
    @Transactional
    public ActorId accept(InvitationAcceptance acceptance) {
        Objects.requireNonNull(acceptance, "acceptance must not be null");
        Objects.requireNonNull(acceptance.invitationId(), "invitationId must not be null");
        Objects.requireNonNull(acceptance.organizationId(), "organizationId must not be null");
        Objects.requireNonNull(acceptance.externalIdentity(), "externalIdentity must not be null");
        requireVerifiedEmail(acceptance.emailVerified());

        String normalizedEmail = normalizeEmail(acceptance.email());
        InvitationRow invitation = lock(acceptance.organizationId(), acceptance.invitationId());
        requireAvailable(invitation);
        return acceptLocked(invitation, acceptance.externalIdentity(), normalizedEmail);
    }

    @Override
    @Transactional
    public ActorId acceptVerifiedEmail(VerifiedEmailInvitationAcceptance acceptance) {
        Objects.requireNonNull(acceptance, "acceptance must not be null");
        Objects.requireNonNull(acceptance.externalIdentity(), "externalIdentity must not be null");
        requireVerifiedEmail(acceptance.emailVerified());

        String normalizedEmail = normalizeEmail(acceptance.email());
        List<InvitationRow> invitations = invitationRepository.findLockedPendingByEmail(
                normalizedEmail,
                clock.instant()
        );
        if (invitations.size() != 1) {
            throw notAvailable();
        }
        InvitationRow invitation = invitations.getFirst();
        requireAvailable(invitation);
        return acceptLocked(invitation, acceptance.externalIdentity(), normalizedEmail);
    }

    private ActorId acceptLocked(
            InvitationRow invitation,
            ExternalIdentity externalIdentity,
            String normalizedEmail
    ) {
        InvitationTarget target = activeTarget(invitation);
        if (!invitation.email().equals(normalizedEmail)) {
            throw new InvitationException(
                    InvitationFailureReason.EMAIL_MISMATCH,
                    "authenticated email does not match invitation"
            );
        }

        try {
            ActorId actorId = identityRegistrar.resolveOrCreateLocked(externalIdentity);
            if (membershipProvisioner.hasAnyMembership(actorId)) {
                throw identityConflict();
            }
            membershipProvisioner.grantMember(target.organizationId(), actorId);
            requireOne(
                    invitationRepository.accept(invitation, actorId, clock.instant()),
                    "accept invitation"
            );
            return actorId;
        } catch (DataIntegrityViolationException exception) {
            throw new InvitationException(
                    InvitationFailureReason.IDENTITY_CONFLICT,
                    "invitation identity or membership conflicts with existing authority",
                    exception
            );
        }
    }

    private InvitationAuthority ownerContext(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return membershipProvisioner.findInvitationAuthority(actorId)
                .orElseThrow(() -> new InvitationException(InvitationFailureReason.NOT_OWNER, "an active Organization owner is required"));
    }

    private InvitationRow pendingOwnedInvitation(ActorId ownerActorId, UUID invitationId) {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        InvitationAuthority owner = ownerContext(ownerActorId);
        InvitationRow invitation = lock(owner.organizationId(), invitationId);
        requireAvailable(invitation);
        return invitation;
    }

    private InvitationRow lock(OrganizationId organizationId, UUID invitationId) {
        return invitationRepository.findLocked(organizationId, invitationId)
                .orElseThrow(DefaultInvitationService::notAvailable);
    }

    private InvitationTarget activeTarget(InvitationRow invitation) {
        return membershipProvisioner.findActiveInvitationTarget(
                        new OrganizationId(invitation.organizationId())
                )
                .orElseThrow(DefaultInvitationService::notAvailable);
    }

    private static InvitationContinuation continuation(
            InvitationRow invitation,
            InvitationTarget target
    ) {
        return new InvitationContinuation(
                invitation.id(),
                target.organizationId(),
                target.organizationDisplayName(),
                invitation.expiresAt()
        );
    }

    private static InvitationView view(InvitationRow invitation) {
        return view(invitation, invitation.expiresAt());
    }

    private static InvitationView view(InvitationRow invitation, Instant expiresAt) {
        return new InvitationView(
                invitation.id(),
                new OrganizationId(invitation.organizationId()),
                invitation.email(),
                invitation.status(),
                invitation.createdAt(),
                expiresAt,
                invitation.acceptedActorId() == null ? null : new ActorId(invitation.acceptedActorId()),
                invitation.acceptedAt(),
                invitation.revokedAt()
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
                    .digest(plaintextSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireAvailable(InvitationRow invitation) {
        if (invitation.status() != InvitationStatus.PENDING
                || !invitation.expiresAt().isAfter(clock.instant())) {
            throw notAvailable();
        }
    }

    private static void requireOne(int updated, String operation) {
        if (updated != 1) {
            throw new InvitationException(InvitationFailureReason.INVITATION_CONFLICT, operation + " affected " + updated + " rows");
        }
    }

    private static InvitationException notAvailable() {
        return new InvitationException(InvitationFailureReason.INVITATION_NOT_AVAILABLE, "invitation is not available");
    }

    private static InvitationException identityConflict() {
        return new InvitationException(InvitationFailureReason.IDENTITY_CONFLICT, "identity already has Organization authority");
    }

}
