package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAccess;
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
    private final JdbcGoogleDriveCredentialRepository credentials;
    private final IamAuthorization authorization;
    private final SourceAccessPolicy sourceAccess;

    public DefaultGoogleDriveAuthorizationService(JdbcGoogleDriveCredentialRepository credentials,
            IamAuthorization authorization, SourceAccessPolicy sourceAccess) {
        this.credentials = credentials;
        this.authorization = authorization;
        this.sourceAccess = sourceAccess;
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
        var stored = credentialId == null ? null : requireCredentialMutation(actorId, tenantId, credentialId);
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
                : requireCredentialMutation(actorId, tenantId, preparation.credentialId());
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
            if (preparation.credentialId() == null) return credentials.create(tenantId, actorId, name, grant, client);
            CredentialId credentialId = preparation.credentialId();
            var stored = requireCredentialMutation(actorId, tenantId, credentialId);
            requireOAuthClientMutation(actorId, tenantId, stored, client);
            credentials.reauthorize(tenantId, credentialId, name, Objects.requireNonNull(preparation.expectedRevision()), grant, client);
            return credentialId;
        }
    }

    @Override
    @Transactional
    public byte[] disconnect(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        var tenant = requireManagement(actorId);
        requireCredentialMutation(actorId, tenant, credentialId);
        return credentials.disconnect(tenant, credentialId, expectedRevision);
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
                            try { sourceAccess.manage(actorId, source); }
                            catch (io.memoryos.BusinessException denied) { editable = false; break; }
                        }
                    }
                    var actions = new java.util.ArrayList<String>();
                    if (editable) {
                        actions.add("reauthorize");
                        if (access.authority() == Authority.GLOBAL) actions.add("replace_oauth_client");
                        if (!"REVOKED".equals(view.status())) actions.add("revoke");
                        if (attached.isEmpty()) actions.add("delete");
                    }
                    return new CredentialView(view.id(), view.name(), view.accountEmail(), view.status(),
                            view.credentialRevision(), view.oauthClientConfigured(), view.createdAt(),
                            view.updatedAt(), view.sourceCount(), List.copyOf(actions));
                }).toList();
    }

    @Override
    @Transactional
    public void delete(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        var tenant = requireManagement(actorId);
        requireCredentialMutation(actorId, tenant, credentialId);
        credentials.delete(tenant, credentialId, expectedRevision);
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
