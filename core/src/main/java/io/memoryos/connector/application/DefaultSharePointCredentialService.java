package io.memoryos.connector.application;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointAuthentication;
import io.memoryos.connector.SharePointCertificate;
import io.memoryos.connector.SharePointCredentialService;
import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.persistence.JdbcSharePointCredentialRepository;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAccess;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.shared.TenantId;
import io.memoryos.shared.ActorId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultSharePointCredentialService implements SharePointCredentialService {
    private static final int MAX_NAME_CHARS = 120;
    /** Verification holds a database-free HTTP call; two at a time keeps an API process responsive. */
    private static final int CONCURRENT_VERIFICATIONS = 2;

    private final JdbcSharePointCredentialRepository credentials;
    private final SharePointProvider provider;
    private final IamAuthorization authorization;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AuditTrail audit;
    private final Semaphore verifications = new Semaphore(CONCURRENT_VERIFICATIONS);

    @Autowired
    public DefaultSharePointCredentialService(JdbcSharePointCredentialRepository credentials, SharePointProvider provider,
            IamAuthorization authorization, PlatformTransactionManager transactionManager,
            AuditTrail audit) {
        this(credentials, provider, authorization, transactionManager, Clock.systemUTC(), audit);
    }

    DefaultSharePointCredentialService(JdbcSharePointCredentialRepository credentials, SharePointProvider provider,
            IamAuthorization authorization, PlatformTransactionManager transactionManager, Clock clock,
            AuditTrail audit) {
        this.audit = audit;
        this.credentials = credentials;
        this.provider = provider;
        this.authorization = authorization;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public CredentialId create(ActorId actorId, Draft draft) {
        String name = requireName(draft.name());
        UUID directoryId = draft.directoryId();
        UUID clientId = draft.clientId();
        transactions.executeWithoutResult(_ -> {
            requireManagement(actorId);
            credentials.requireConfigured();
        });
        try (var authentication = authentication(draft)) {
            var verification = verify(draft.cloud(), directoryId, clientId, authentication);
            return Objects.requireNonNull(transactions.execute(_ -> {
                TenantId tenantId = requireManagement(actorId);
                var created = credentials.create(tenantId, actorId, name, directoryId, clientId, draft.cloud(),
                        authentication, verification.tenantHost());
                audit.record(AuditRecord.of(AuditAction.CREDENTIAL_CREATE, tenantId).actor(actorId).resource("CREDENTIAL", created.value(), name).detail("provider", "SHAREPOINT").detail("authentication", draft.authMethod().name()).build());
                return created;
            }));
        }
    }

    @Override
    public long replaceAuthentication(ActorId actorId, CredentialId credentialId, long expectedRevision, Draft draft) {
        String name = requireName(draft.name());
        UUID directoryId = draft.directoryId();
        UUID clientId = draft.clientId();
        transactions.executeWithoutResult(_ -> {
            var stored = requireCredentialMutation(actorId, credentialId);
            if (!stored.directoryId().equals(directoryId) || !stored.clientId().equals(clientId)) {
                throw SourceException.conflict("SharePoint credential targets a different Entra application");
            }
            credentials.requireConfigured();
        });
        try (var authentication = authentication(draft)) {
            var verification = verify(draft.cloud(), directoryId, clientId, authentication);
            return Objects.requireNonNull(transactions.execute(_ -> {
                TenantId tenantId = requireManagement(actorId);
                requireCredentialMutation(actorId, credentialId);
                long revision = credentials.replaceAuthentication(tenantId, credentialId, expectedRevision, name,
                        authentication, verification.tenantHost());
                audit.record(AuditRecord.of(AuditAction.CREDENTIAL_UPDATE, tenantId).actor(actorId).resource("CREDENTIAL", credentialId.value(), name).detail("provider", "SHAREPOINT").detail("change", "REPLACE_AUTHENTICATION").build());
                return revision;
            }));
        }
    }

    @Override
    @Transactional
    public void rename(ActorId actorId, CredentialId credentialId, long expectedRevision, String name) {
        TenantId tenantId = requireManagement(actorId);
        requireCredentialMutation(actorId, credentialId);
        credentials.rename(tenantId, credentialId, expectedRevision, requireName(name));
        audit.record(AuditRecord.of(AuditAction.CREDENTIAL_UPDATE, tenantId).actor(actorId).resource("CREDENTIAL", credentialId.value(), requireName(name)).detail("provider", "SHAREPOINT").detail("change", "RENAME").build());
    }

    @Override
    @Transactional
    public void delete(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        TenantId tenantId = requireManagement(actorId);
        requireCredentialMutation(actorId, credentialId);
        credentials.delete(tenantId, credentialId, expectedRevision);
        audit.record(AuditRecord.of(AuditAction.CREDENTIAL_DELETE, tenantId).actor(actorId).resource("CREDENTIAL", credentialId.value(), null).detail("provider", "SHAREPOINT").build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialView> list(ActorId actorId) {
        var access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
        boolean global = access.authority() == Authority.GLOBAL;
        return credentials.list(access.tenantId(), global ? null : actorId).stream().map(view -> {
            var actions = new ArrayList<String>(List.of("rename", "replace_authentication", "test"));
            if (view.sourceCount() == 0) actions.add("delete");
            return new CredentialView(view.id(), view.name(), view.directoryId(), view.clientId(), view.cloud(),
                    view.authMethod(), view.status(), view.certificateThumbprint(), view.certificateNotAfter(),
                    view.tenantHost(), view.credentialRevision(), view.createdAt(), view.updatedAt(),
                    view.sourceCount(), List.copyOf(actions));
        }).toList();
    }

    @Override
    public TestResult test(ActorId actorId, CredentialId credentialId) {
        var loaded = Objects.requireNonNull(transactions.execute(_ -> {
            TenantId tenant = requireManagement(actorId);
            var row = requireCredentialMutation(actorId, credentialId);
            if (!row.usable()) throw SharePointException.needsUpdate();
            return new Loaded(tenant, row);
        }));
        TenantId tenantId = loaded.tenantId();
        var stored = loaded.stored();
        try (var authentication = credentials.authentication(tenantId, stored)) {
            Verification verification;
            try {
                verification = verify(stored.cloud(), stored.directoryId(), stored.clientId(), authentication);
            } catch (SharePointException rejected) {
                if (SharePointException.isCredentialRejection(rejected.code())) {
                    transactions.executeWithoutResult(_ -> credentials.markNeedsUpdate(tenantId, credentialId, stored.revision()));
                }
                throw rejected;
            }
            transactions.executeWithoutResult(_ ->
                    credentials.recordTenantHost(tenantId, credentialId, stored.revision(), verification.tenantHost()));
            return new TestResult(verification.allSitesReadable(), verification.tenantHost());
        }
    }

    /**
     * Asks Microsoft for a token and reads {@code /sites/root}. A rejected token means nothing is stored;
     * a token that cannot read the whole Tenant is still a working credential.
     */
    private Verification verify(SharePointProvider.Cloud cloud, UUID directoryId, UUID clientId,
            SharePointAuthentication authentication) {
        if (!verifications.tryAcquire()) throw SharePointException.unavailable();
        try (var credential = authentication.credential(cloud, directoryId.toString(), clientId.toString())) {
            try (var session = provider.open(credential)) {
                var root = session.root();
                return new Verification(true, root.hostname());
            }
        } catch (SharePointProviderException exception) {
            return switch (exception.failure()) {
                case AUTHENTICATION -> throw SharePointException.rejected(exception.reason());
                case AUTHORIZATION, NOT_FOUND -> new Verification(false, null);
                default -> throw SharePointException.unavailable();
            };
        } finally {
            verifications.release();
        }
    }

    private SharePointAuthentication authentication(Draft draft) {
        if (draft.authMethod() == SharePointProvider.AuthMethod.CLIENT_SECRET) {
            byte[] secret = draft.clientSecret();
            if (secret == null) throw SharePointException.invalidSecret();
            return SharePointAuthentication.clientSecret(secret);
        }
        byte[] pkcs12 = draft.pkcs12();
        char[] password = draft.pkcs12Password();
        if (pkcs12 == null || password == null) {
            throw SharePointException.invalidCertificate("Upload a PKCS#12 (.pfx) file and its password.",
                    "SharePoint certificate draft is missing the keystore or its password");
        }
        try {
            return SharePointAuthentication.certificate(SharePointCertificate.read(pkcs12, password, clock.instant()));
        } finally {
            java.util.Arrays.fill(pkcs12, (byte) 0);
            java.util.Arrays.fill(password, '\0');
        }
    }

    private TenantId requireManagement(ActorId actorId) {
        return authorization.lockAndRequireScopedMutation(Objects.requireNonNull(actorId), IamCapability.SOURCES_MANAGE).tenantId();
    }

    private JdbcSharePointCredentialRepository.Stored requireCredentialMutation(ActorId actorId, CredentialId credentialId) {
        IamAccess access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
        var stored = credentials.lock(access.tenantId(), credentialId).orElseThrow(SourceException::notFound);
        requireCredentialOwner(access, actorId, stored.ownerActorId());
        return stored;
    }

    private static void requireCredentialOwner(IamAccess access, ActorId actor, @Nullable UUID ownerActorId) {
        if (access.authority() != Authority.GLOBAL && !actor.value().equals(ownerActorId)) throw SourceException.notFound();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_CHARS) {
            throw SourceException.invalid("Credential name must contain 1 to 120 characters.", "invalid credential name");
        }
        return name.strip();
    }

    private record Verification(boolean allSitesReadable, @Nullable String tenantHost) {}

    private record Loaded(TenantId tenantId, JdbcSharePointCredentialRepository.Stored stored) {}
}
