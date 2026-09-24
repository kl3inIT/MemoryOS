package io.memoryos.connector.googledrive;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleGroupRepository;
import io.memoryos.connector.source.SourceAccessPolicy;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultGoogleDriveAuthorizationService implements GoogleDriveAuthorizationService {
    private static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";
    private final JdbcGoogleDriveCredentialRepository credentials;
    private final IamAuthorization authorization;
    private final SourceAccessPolicy sourceAccess;
    private final JdbcGoogleGroupRepository groups;
    private final AuditTrail audit;

    public DefaultGoogleDriveAuthorizationService(JdbcGoogleDriveCredentialRepository credentials,
            IamAuthorization authorization, SourceAccessPolicy sourceAccess, JdbcGoogleGroupRepository groups,
            AuditTrail audit) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.credentials = credentials;
        this.authorization = authorization;
        this.sourceAccess = sourceAccess;
        this.groups = groups;
    }

    @Override
    @Transactional
    public Preparation prepare(ActorId actorId, String name, @Nullable CredentialId credentialId, @Nullable Long expectedRevision,
            @Nullable GoogleDriveOAuthClient oauthClient) {
        TenantId tenantId = requireManagement(actorId);
        String normalized = requireName(name);
        if ((credentialId == null) != (expectedRevision == null) || (expectedRevision != null && expectedRevision < 1)) {
            throw SourceException.invalid("Credential and revision must be supplied together.", "invalid OAuth reauthorization target");
        }
        var preparation = new Preparation(tenantId, normalized, credentialId, expectedRevision, UUID.randomUUID(), "");
        var stored = credentialId == null ? null : requireOAuthCredential(requireCredentialMutation(actorId, tenantId, credentialId));
        if (stored != null && stored.revision() != expectedRevision) throw SourceException.conflict("Google credential revision is stale");
        if (stored != null && oauthClient != null) authorization.require(actorId, IamCapability.SOURCES_MANAGE, false);
        if (oauthClient == null && stored == null) throw GoogleDriveException.oauthClientRequired();
        credentials.requireConfigured();
        String snapshot;
        if (oauthClient != null) {
            snapshot = credentials.snapshot(actorId, preparation, oauthClient);
        } else {
            try (var reused = credentials.oauthClient(tenantId, Objects.requireNonNull(stored))) {
                snapshot = credentials.snapshot(actorId, preparation, reused);
            }
        }
        return new Preparation(tenantId, normalized, credentialId, expectedRevision, preparation.consentId(), snapshot);
    }

    @Override
    @Transactional
    public GoogleDriveOAuthClient oauthClient(ActorId actorId, Preparation preparation) {
        TenantId tenantId = requireManagement(actorId);
        if (!tenantId.equals(preparation.tenantId())) throw SourceException.notFound();
        var stored = preparation.credentialId() == null ? null
                : requireOAuthCredential(requireCredentialMutation(actorId, tenantId, preparation.credentialId()));
        if (stored != null && !Objects.equals(stored.revision(), preparation.expectedRevision()))
            throw SourceException.conflict("Google credential revision is stale");
        var client = credentials.snapshot(actorId, preparation);
        try {
            if (stored != null) requireOAuthClientMutation(actorId, tenantId, stored, client);
            return client;
        } catch (RuntimeException | Error exception) {
            client.close();
            throw exception;
        }
    }

    @Override
    @Transactional
    public CredentialId complete(ActorId actorId, Preparation preparation, Grant grant) {
        TenantId tenantId = requireManagement(actorId);
        if (!tenantId.equals(preparation.tenantId())) throw SourceException.notFound();
        requireGrant(grant);
        try (var client = credentials.snapshot(actorId, preparation)) {
            String name = requireName(preparation.name());
            if (preparation.credentialId() == null) {
                var created = credentials.create(tenantId, actorId, name, grant, client);
                audit.record(AuditRecord.of(AuditAction.CREDENTIAL_CREATE, tenantId).actor(actorId).resource("CREDENTIAL", created.value(), name).detail("provider", "GOOGLE_DRIVE").detail("authentication", "OAUTH").build());
                return created;
            }
            CredentialId credentialId = preparation.credentialId();
            var stored = requireOAuthCredential(requireCredentialMutation(actorId, tenantId, credentialId));
            requireOAuthClientMutation(actorId, tenantId, stored, client);
            credentials.reauthorize(tenantId, credentialId, name, Objects.requireNonNull(preparation.expectedRevision()), grant, client);
            audit.record(AuditRecord.of(AuditAction.CREDENTIAL_UPDATE, tenantId).actor(actorId).resource("CREDENTIAL", credentialId.value(), name).detail("provider", "GOOGLE_DRIVE").detail("change", "REAUTHORIZE").build());
            return credentialId;
        }
    }

    @Override
    @Transactional
    public byte[] disconnect(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        var tenant = requireManagement(actorId);
        requireCredentialMutation(actorId, tenant, credentialId);
        byte[] revoked = credentials.disconnect(tenant, credentialId, expectedRevision);
        // A revoked service account's Google Group memberships stop granting access at once.
        groups.removeAll(tenant, credentialId);
        audit.record(AuditRecord.of(AuditAction.CREDENTIAL_UPDATE, tenant).actor(actorId).resource("CREDENTIAL", credentialId.value(), null).detail("provider", "GOOGLE_DRIVE").detail("change", "REVOKE").build());
        return revoked;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialView> list(ActorId actorId) {
        var access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
        return credentials.list(access.tenantId(), access.authority() == Authority.GLOBAL ? null : actorId).stream()
                .map(view -> {
                    var attached = credentials.attachedSources(access.tenantId(), view.id());
                    boolean editable = access.authority() == Authority.GLOBAL;
                    if (!editable) {
                        editable = true;
                        for (var source : attached) {
                            if (!sourceAccess.canManage(actorId, source)) { editable = false; break; }
                        }
                    }
                    var actions = new java.util.ArrayList<String>();
                    if (editable) {
                        if (SERVICE_ACCOUNT.equals(view.authMethod())) {
                            actions.add("replace_key");
                        } else {
                            actions.add("reauthorize");
                            if (access.authority() == Authority.GLOBAL) actions.add("replace_oauth_client");
                        }
                        if (!"REVOKED".equals(view.status())) actions.add("revoke");
                        if (attached.isEmpty()) actions.add("delete");
                    }
                    return new CredentialView(view.id(), view.name(), view.accountEmail(), view.status(),
                            view.credentialRevision(), view.authMethod(), view.serviceAccountEmail(), view.oauthClientConfigured(), view.createdAt(),
                            view.updatedAt(), view.sourceCount(), List.copyOf(actions));
                }).toList();
    }

    @Override
    @Transactional
    public void delete(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        var tenant = requireManagement(actorId);
        requireCredentialMutation(actorId, tenant, credentialId);
        credentials.delete(tenant, credentialId, expectedRevision);
        audit.record(AuditRecord.of(AuditAction.CREDENTIAL_DELETE, tenant).actor(actorId).resource("CREDENTIAL", credentialId.value(), null).detail("provider", "GOOGLE_DRIVE").build());
    }

    private TenantId requireManagement(ActorId actorId) {
        return authorization.lockAndRequireScopedMutation(Objects.requireNonNull(actorId), IamCapability.SOURCES_MANAGE).tenantId();
    }

    private JdbcGoogleDriveCredentialRepository.Stored requireCredentialMutation(ActorId actor, TenantId tenant, CredentialId credential) {
        var access = authorization.require(actor, IamCapability.SOURCES_MANAGE, true);
        if (!tenant.equals(access.tenantId())) throw SourceException.notFound();
        var stored = credentials.lock(tenant, credential).orElseThrow(SourceException::notFound);
        requireCredentialOwner(access, actor, stored);
        if (access.authority() != Authority.GLOBAL)
            for (var source : credentials.attachedSources(tenant, credential)) sourceAccess.lockManage(actor, source);
        return stored;
    }

    /** A service account changes only by replacing its key, never through OAuth consent. */
    private static JdbcGoogleDriveCredentialRepository.Stored requireOAuthCredential(JdbcGoogleDriveCredentialRepository.Stored stored) {
        if (stored.serviceAccount()) throw SourceException.conflict("Replace the key of a Google service account instead");
        return stored;
    }

    private void requireOAuthClientMutation(ActorId actor, TenantId tenant,
            JdbcGoogleDriveCredentialRepository.Stored stored, GoogleDriveOAuthClient client) {
        if (authorization.require(actor, IamCapability.SOURCES_MANAGE, true).authority() == Authority.GLOBAL) return;
        if (stored.oauthClientConfigured()) {
            try (var saved = credentials.oauthClient(tenant, stored)) {
                if (client.clientId().equals(saved.clientId())) {
                    byte[] secret = client.clientSecret();
                    byte[] savedSecret = saved.clientSecret();
                    try {
                        if (MessageDigest.isEqual(secret, savedSecret)) return;
                    } finally {
                        Arrays.fill(secret, (byte) 0);
                        Arrays.fill(savedSecret, (byte) 0);
                    }
                }
            }
        }
        authorization.require(actor, IamCapability.SOURCES_MANAGE, false);
    }

    static void requireCredentialOwner(IamAccess access, ActorId actor, JdbcGoogleDriveCredentialRepository.Stored stored) {
        if (access.authority() != Authority.GLOBAL && !actor.value().equals(stored.ownerActorId()))
            throw SourceException.notFound();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw SourceException.invalid("Credential name must contain 1 to 120 characters.", "invalid credential name");
        }
        return name.strip();
    }

    private static void requireGrant(Grant grant) {
        byte[] token = grant.refreshToken();
        try {
            if (grant.accountSubject().isBlank() || grant.accountSubject().length() > 255
                    || grant.accountEmail().isBlank() || grant.accountEmail().length() > 320
                    || token.length == 0 || token.length > 16384
                    || !grant.scopes().containsAll(REQUIRED_SCOPES)
                    || !(grant.scopes().contains("email")
                        || grant.scopes().contains("https://www.googleapis.com/auth/userinfo.email"))) {
                throw SourceException.invalid("Google did not grant the required read-only authorization.", "incomplete Google authorization grant");
            }
        } finally { Arrays.fill(token, (byte) 0); }
    }
}
