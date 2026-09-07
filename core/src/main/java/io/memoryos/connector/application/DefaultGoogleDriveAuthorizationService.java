package io.memoryos.connector.application;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;
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
    private final TenantAccessResolver tenants;

    public DefaultGoogleDriveAuthorizationService(JdbcGoogleDriveCredentialRepository credentials,
            TenantAccessResolver tenants) {
        this.credentials = credentials;
        this.tenants = tenants;
    }

    @Override
    @Transactional
    public Preparation prepare(ActorId actorId, String name, @Nullable CredentialId credentialId, @Nullable Long expectedRevision,
            @Nullable GoogleDriveOAuthClient oauthClient) {
        TenantId tenantId = requireOwner(actorId);
        String normalized = requireName(name);
        if ((credentialId == null) != (expectedRevision == null) || (expectedRevision != null && expectedRevision < 1)) {
            throw SourceException.invalid("Credential and revision must be supplied together.", "invalid OAuth reauthorization target");
        }
        var preparation = new Preparation(tenantId, normalized, credentialId, expectedRevision, UUID.randomUUID(), "");
        var stored = credentialId == null ? null : credentials.lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        if (stored != null && stored.revision() != expectedRevision) throw SourceException.conflict("Google credential revision is stale");
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
        TenantId tenantId = requireOwner(actorId);
        if (!tenantId.equals(preparation.tenantId())) throw SourceException.notOwner();
        if (preparation.credentialId() != null) {
            var stored = credentials.lock(tenantId, preparation.credentialId()).orElseThrow(SourceException::notFound);
            if (!Objects.equals(stored.revision(), preparation.expectedRevision())) throw SourceException.conflict("Google credential revision is stale");
        }
        return credentials.snapshot(actorId, preparation);
    }

    @Override
    @Transactional
    public CredentialId complete(ActorId actorId, Preparation preparation, Grant grant) {
        TenantId tenantId = requireOwner(actorId);
        if (!tenantId.equals(preparation.tenantId())) throw SourceException.notOwner();
        requireGrant(grant);
        try (var client = credentials.snapshot(actorId, preparation)) {
            String name = requireName(preparation.name());
            if (preparation.credentialId() == null) return credentials.create(tenantId, name, grant, client);
            CredentialId credentialId = preparation.credentialId();
            credentials.reauthorize(tenantId, credentialId, name, Objects.requireNonNull(preparation.expectedRevision()), grant, client);
            return credentialId;
        }
    }

    @Override
    @Transactional
    public byte[] disconnect(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        return credentials.disconnect(requireOwner(actorId), credentialId, expectedRevision);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialView> list(ActorId actorId) {
        return credentials.list(requireOwner(actorId));
    }

    @Override
    @Transactional
    public void delete(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        credentials.delete(requireOwner(actorId), credentialId, expectedRevision);
    }

    private TenantId requireOwner(ActorId actorId) {
        return tenants.findActiveOwnerTenant(Objects.requireNonNull(actorId)).orElseThrow(SourceException::notOwner);
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
