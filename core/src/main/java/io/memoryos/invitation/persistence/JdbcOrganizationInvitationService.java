package io.memoryos.invitation.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.invitation.OrganizationInvitationException;
import io.memoryos.invitation.OrganizationInvitationService;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.OrganizationMembershipProvisioner;
import io.memoryos.organization.WorkspaceId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcOrganizationInvitationService implements OrganizationInvitationService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int SECRET_BYTES = 32;


    private static final String EXPIRE_PENDING_EMAIL = """
            UPDATE organization_invitations
            SET status = 'EXPIRED',
                open_email_key = NULL,
                updated_at = :now
            WHERE organization_id = :organizationId
              AND open_email_key = :email
              AND status = 'PENDING'
              AND expires_at <= :now
            """;

    private static final String EXPIRE_PENDING_ORGANIZATION = """
            UPDATE organization_invitations
            SET status = 'EXPIRED',
                open_email_key = NULL,
                updated_at = :now
            WHERE organization_id = :organizationId
              AND status = 'PENDING'
              AND expires_at <= :now
            """;

    private static final String INSERT_INVITATION = """
            INSERT INTO organization_invitations (
                id,
                organization_id,
                default_workspace_id,
                normalized_email,
                open_email_key,
                secret_digest,
                secret_version,
                status,
                created_by_actor_id,
                created_at,
                updated_at,
                expires_at
            )
            VALUES (
                :id,
                :organizationId,
                :workspaceId,
                :email,
                :email,
                :digest,
                1,
                'PENDING',
                :actorId,
                :now,
                :now,
                :expiresAt
            )
            """;

    private static final String SELECT_INVITATIONS = """
            SELECT invitation.*
            FROM organization_invitations invitation
            WHERE invitation.organization_id = :organizationId
            ORDER BY invitation.created_at DESC, invitation.id
            """;

    private static final String LOCK_INVITATION = """
            SELECT invitation.*
            FROM organization_invitations invitation
            WHERE invitation.organization_id = :organizationId
              AND invitation.id = :invitationId
            FOR UPDATE
            """;

    private static final String LOCK_INVITATION_BY_DIGEST = """
            SELECT invitation.*
            FROM organization_invitations invitation
            WHERE invitation.secret_digest = :digest
            FOR UPDATE
            """;

    private static final String ROTATE_INVITATION = """
            UPDATE organization_invitations
            SET secret_digest = :digest,
                secret_version = secret_version + 1,
                expires_at = :expiresAt,
                updated_at = :now
            WHERE organization_id = :organizationId
              AND id = :invitationId
              AND status = 'PENDING'
            """;

    private static final String REVOKE_INVITATION = """
            UPDATE organization_invitations
            SET status = 'REVOKED',
                open_email_key = NULL,
                revoked_by_actor_id = :actorId,
                revoked_at = :now,
                updated_at = :now
            WHERE organization_id = :organizationId
              AND id = :invitationId
              AND status = 'PENDING'
            """;


    private static final String ACCEPT_INVITATION = """
            UPDATE organization_invitations
            SET status = 'ACCEPTED',
                open_email_key = NULL,
                accepted_by_actor_id = :actorId,
                accepted_at = :now,
                updated_at = :now
            WHERE organization_id = :organizationId
              AND id = :invitationId
              AND status = 'PENDING'
            """;

    private final JdbcClient jdbcClient;
    private final ExternalIdentityResolver identityResolver;
    private final ExternalIdentityRegistrar identityRegistrar;
    private final OrganizationMembershipProvisioner membershipProvisioner;
    private final Clock clock;
    private final Duration timeToLive;
    private final SecureRandom secureRandom;

    public JdbcOrganizationInvitationService(
            JdbcClient jdbcClient,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar,
            OrganizationMembershipProvisioner membershipProvisioner,
            Clock clock,
            Duration timeToLive,
            SecureRandom secureRandom
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.identityRegistrar = Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null");
        this.membershipProvisioner = Objects.requireNonNull(
                membershipProvisioner,
                "membershipProvisioner must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeToLive = requireTimeToLive(timeToLive);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    @Transactional
    public IssuedInvitation issue(ActorId ownerActorId, String email) {
        var owner = ownerContext(ownerActorId);
        String normalizedEmail = normalizeEmail(email);
        Instant now = clock.instant();
        jdbcClient.sql(EXPIRE_PENDING_EMAIL)
                .param("organizationId", owner.organizationId().value())
                .param("email", normalizedEmail)
                .param("now", sqlTime(now))
                .update();

        UUID invitationId = UUID.randomUUID();
        String plaintextSecret = newSecret();
        Instant expiresAt = now.plus(timeToLive);
        try {
            requireOne(jdbcClient.sql(INSERT_INVITATION)
                    .param("id", invitationId)
                    .param("organizationId", owner.organizationId().value())
                    .param("workspaceId", owner.defaultWorkspaceId().value())
                    .param("email", normalizedEmail)
                    .param("digest", digest(plaintextSecret))
                    .param("actorId", ownerActorId.value())
                    .param("now", sqlTime(now))
                    .param("expiresAt", sqlTime(expiresAt))
                    .update(), "create invitation");
        } catch (DataIntegrityViolationException exception) {
            throw failure(
                    OrganizationInvitationException.Reason.INVITATION_CONFLICT,
                    "an open invitation already exists for this email",
                    exception
            );
        }

        return new IssuedInvitation(
                new InvitationView(
                        invitationId,
                        owner.organizationId(),
                        owner.defaultWorkspaceId(),
                        normalizedEmail,
                        Status.PENDING,
                        1,
                        now,
                        expiresAt,
                        null,
                        null,
                        null
                ),
                plaintextSecret
        );
    }

    @Override
    @Transactional
    public List<InvitationView> list(ActorId ownerActorId) {
        var owner = ownerContext(ownerActorId);
        Instant now = clock.instant();
        jdbcClient.sql(EXPIRE_PENDING_ORGANIZATION)
                .param("organizationId", owner.organizationId().value())
                .param("now", sqlTime(now))
                .update();
        return jdbcClient.sql(SELECT_INVITATIONS)
                .param("organizationId", owner.organizationId().value())
                .query((resultSet, ignored) -> invitation(resultSet))
                .list();
    }

    @Override
    @Transactional
    public IssuedInvitation rotate(ActorId ownerActorId, UUID invitationId) {
        LockedInvitation invitation = pendingOwnedInvitation(ownerActorId, invitationId);
        Instant now = clock.instant();
        String plaintextSecret = newSecret();
        Instant expiresAt = now.plus(timeToLive);
        try {
            requireOne(jdbcClient.sql(ROTATE_INVITATION)
                    .param("organizationId", invitation.organizationId())
                    .param("invitationId", invitationId)
                    .param("digest", digest(plaintextSecret))
                    .param("expiresAt", sqlTime(expiresAt))
                    .param("now", sqlTime(now))
                    .update(), "rotate invitation");
        } catch (DataIntegrityViolationException exception) {
            throw failure(
                    OrganizationInvitationException.Reason.INVITATION_CONFLICT,
                    "could not rotate invitation",
                    exception
            );
        }

        return new IssuedInvitation(
                invitation.toPendingView(invitation.secretVersion() + 1, expiresAt),
                plaintextSecret
        );
    }

    @Override
    @Transactional
    public void revoke(ActorId ownerActorId, UUID invitationId) {
        LockedInvitation invitation = pendingOwnedInvitation(ownerActorId, invitationId);
        Instant now = clock.instant();
        requireOne(jdbcClient.sql(REVOKE_INVITATION)
                .param("organizationId", invitation.organizationId())
                .param("invitationId", invitationId)
                .param("actorId", ownerActorId.value())
                .param("now", sqlTime(now))
                .update(), "revoke invitation");
    }

    @Override
    @Transactional
    public InvitationContinuation intake(String plaintextSecret) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw notAvailable();
        }
        LockedInvitation invitation = jdbcClient.sql(LOCK_INVITATION_BY_DIGEST)
                .param("digest", digest(plaintextSecret))
                .query((resultSet, ignored) -> lockedInvitation(resultSet))
                .optional()
                .orElseThrow(JdbcOrganizationInvitationService::notAvailable);
        requirePending(invitation);
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw notAvailable();
        }
        var target = activeTarget(invitation);
        return new InvitationContinuation(
                invitation.id(),
                target.organizationId(),
                target.organizationDisplayName(),
                invitation.expiresAt()
        );
    }

    @Override
    @Transactional
    public InvitationContinuation resume(UUID invitationId, OrganizationId organizationId) {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        LockedInvitation invitation = lock(organizationId.value(), invitationId);
        requirePending(invitation);
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw notAvailable();
        }
        var target = activeTarget(invitation);
        return new InvitationContinuation(
                invitation.id(),
                target.organizationId(),
                target.organizationDisplayName(),
                invitation.expiresAt()
        );
    }

    @Override
    @Transactional
    public ActorId accept(InvitationAcceptance acceptance) {
        Objects.requireNonNull(acceptance, "acceptance must not be null");
        Objects.requireNonNull(acceptance.invitationId(), "invitationId must not be null");
        Objects.requireNonNull(acceptance.organizationId(), "organizationId must not be null");
        Objects.requireNonNull(acceptance.externalIdentity(), "externalIdentity must not be null");
        if (!acceptance.emailVerified()) {
            throw failure(
                    OrganizationInvitationException.Reason.EMAIL_NOT_VERIFIED,
                    "invitation email is not verified"
            );
        }

        String normalizedEmail = normalizeEmail(acceptance.email());
        LockedInvitation invitation = lock(
                acceptance.organizationId().value(),
                acceptance.invitationId()
        );
        requirePending(invitation);
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw notAvailable();
        }
        var target = activeTarget(invitation);
        if (!invitation.email().equals(normalizedEmail)) {
            throw failure(
                    OrganizationInvitationException.Reason.EMAIL_MISMATCH,
                    "authenticated email does not match invitation"
            );
        }

        ActorId existingActor = identityResolver.resolve(acceptance.externalIdentity()).orElse(null);
        if (existingActor != null && membershipProvisioner.hasAnyMembership(existingActor)) {
            throw identityConflict();
        }

        try {
            ActorId actorId = identityRegistrar.resolveOrCreate(acceptance.externalIdentity());
            if (membershipProvisioner.hasAnyMembership(actorId)) {
                throw identityConflict();
            }
            membershipProvisioner.grantDefaultMember(
                    target.organizationId(),
                    target.defaultWorkspaceId(),
                    actorId
            );
            requireOne(jdbcClient.sql(ACCEPT_INVITATION)
                    .param("organizationId", invitation.organizationId())
                    .param("invitationId", invitation.id())
                    .param("actorId", actorId.value())
                    .param("now", sqlTime(clock.instant()))
                    .update(), "accept invitation");
            return actorId;
        } catch (DataIntegrityViolationException exception) {
            throw failure(
                    OrganizationInvitationException.Reason.IDENTITY_CONFLICT,
                    "invitation identity or membership conflicts with existing authority",
                    exception
            );
        }
    }

    private OrganizationMembershipProvisioner.InvitationAuthority ownerContext(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return membershipProvisioner.findInvitationAuthority(actorId)
                .orElseThrow(() -> failure(
                        OrganizationInvitationException.Reason.NOT_OWNER,
                        "an active Organization owner is required"
                ));
    }

    private LockedInvitation pendingOwnedInvitation(ActorId ownerActorId, UUID invitationId) {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        var owner = ownerContext(ownerActorId);
        LockedInvitation invitation = lock(owner.organizationId().value(), invitationId);
        requirePending(invitation);
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw notAvailable();
        }
        return invitation;
    }

    private LockedInvitation lock(UUID organizationId, UUID invitationId) {
        return jdbcClient.sql(LOCK_INVITATION)
                .param("organizationId", organizationId)
                .param("invitationId", invitationId)
                .query((resultSet, ignored) -> lockedInvitation(resultSet))
                .optional()
                .orElseThrow(JdbcOrganizationInvitationService::notAvailable);
    }

    private OrganizationMembershipProvisioner.InvitationTarget activeTarget(LockedInvitation invitation) {
        return membershipProvisioner.findActiveInvitationTarget(
                        new OrganizationId(invitation.organizationId()),
                        new WorkspaceId(invitation.defaultWorkspaceId())
                )
                .orElseThrow(JdbcOrganizationInvitationService::notAvailable);
    }

    private static InvitationView invitation(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        UUID acceptedActor = resultSet.getObject("accepted_by_actor_id", UUID.class);
        return new InvitationView(
                resultSet.getObject("id", UUID.class),
                new OrganizationId(resultSet.getObject("organization_id", UUID.class)),
                new WorkspaceId(resultSet.getObject("default_workspace_id", UUID.class)),
                resultSet.getString("normalized_email"),
                Status.valueOf(resultSet.getString("status")),
                resultSet.getInt("secret_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                acceptedActor == null ? null : new ActorId(acceptedActor),
                instant(resultSet, "accepted_at"),
                instant(resultSet, "revoked_at")
        );
    }

    private static LockedInvitation lockedInvitation(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new LockedInvitation(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("default_workspace_id", UUID.class),
                resultSet.getString("normalized_email"),
                Status.valueOf(resultSet.getString("status")),
                resultSet.getInt("secret_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getObject("accepted_by_actor_id", UUID.class),
                instant(resultSet, "accepted_at"),
                instant(resultSet, "revoked_at")
        );
    }

    private static Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Duration requireTimeToLive(Duration value) {
        Objects.requireNonNull(value, "timeToLive must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        return value;
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            throw failure(OrganizationInvitationException.Reason.INVALID_EMAIL, "email is required");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw failure(OrganizationInvitationException.Reason.INVALID_EMAIL, "email is invalid");
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

    private static OffsetDateTime sqlTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requirePending(LockedInvitation invitation) {
        if (invitation.status() != Status.PENDING) {
            throw notAvailable();
        }
    }

    private static void requireOne(int updated, String operation) {
        if (updated != 1) {
            throw failure(
                    OrganizationInvitationException.Reason.INVITATION_CONFLICT,
                    operation + " affected " + updated + " rows"
            );
        }
    }

    private static OrganizationInvitationException notAvailable() {
        return failure(
                OrganizationInvitationException.Reason.INVITATION_NOT_AVAILABLE,
                "invitation is not available"
        );
    }

    private static OrganizationInvitationException identityConflict() {
        return failure(
                OrganizationInvitationException.Reason.IDENTITY_CONFLICT,
                "identity already has Organization authority"
        );
    }

    private static OrganizationInvitationException failure(
            OrganizationInvitationException.Reason reason,
            String message
    ) {
        return new OrganizationInvitationException(reason, message);
    }

    private static OrganizationInvitationException failure(
            OrganizationInvitationException.Reason reason,
            String message,
            Throwable cause
    ) {
        var exception = new OrganizationInvitationException(reason, message);
        exception.initCause(cause);
        return exception;
    }


    private record LockedInvitation(
            UUID id,
            UUID organizationId,
            UUID defaultWorkspaceId,
            String email,
            Status status,
            int secretVersion,
            Instant createdAt,
            Instant expiresAt,
            UUID acceptedActorId,
            Instant acceptedAt,
            Instant revokedAt
    ) {

        private InvitationView toPendingView(int nextVersion, Instant nextExpiry) {
            return new InvitationView(
                    id,
                    new OrganizationId(organizationId),
                    new WorkspaceId(defaultWorkspaceId),
                    email,
                    Status.PENDING,
                    nextVersion,
                    createdAt,
                    nextExpiry,
                    acceptedActorId == null ? null : new ActorId(acceptedActorId),
                    acceptedAt,
                    revokedAt
            );
        }
    }
}
