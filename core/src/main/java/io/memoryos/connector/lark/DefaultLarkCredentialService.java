package io.memoryos.connector.lark;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkCredentialService;
import io.memoryos.connector.LarkException;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultLarkCredentialService implements LarkCredentialService {
    private static final int MAX_NAME_CHARS = 120;
    private static final int MAX_SECRET_CHARS = 256;
    private static final Pattern APP_ID = Pattern.compile("cli_[0-9a-z]{8,40}");
    /** Verification holds a database-free HTTP call; two at a time keeps an API process responsive. */
    private static final int CONCURRENT_VERIFICATIONS = 2;

    private final JdbcLarkCredentialRepository credentials;
    private final LarkGateway provider;
    private final IamAuthorization authorization;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final Semaphore verifications = new Semaphore(CONCURRENT_VERIFICATIONS);

    public DefaultLarkCredentialService(JdbcLarkCredentialRepository credentials, LarkGateway provider,
            IamAuthorization authorization, PlatformTransactionManager transactionManager, AuditTrail audit) {
        this.credentials = credentials;
        this.provider = provider;
        this.authorization = authorization;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
    }

    @Override
    public Created create(ActorId actorId, Draft draft) {
        String name = requireName(draft.name());
        String appId = requireAppId(draft.appId());
        byte[] secret = requireSecret(draft.appSecret());
        transactions.executeWithoutResult(_ -> {
            requireManagement(actorId);
            credentials.requireConfigured();
        });
        Verification verification;
        try (var credential = new LarkGateway.Credential(draft.region(), appId, secret)) {
            verification = verify(credential);
        }
        var created = Objects.requireNonNull(transactions.execute(_ -> {
            TenantId tenantId = requireManagement(actorId);
            var id = credentials.create(tenantId, actorId, name, draft.region(), appId, secret, verification.bot());
            audit.record(AuditRecord.of(AuditAction.CREDENTIAL_CREATE, tenantId).actor(actorId)
                    .resource("CREDENTIAL", id.value(), name).detail("provider", "LARK")
                    .detail("authentication", "APP_SECRET").build());
            return id;
        }));
        Arrays.fill(secret, (byte) 0);
        return new Created(created, verification.result());
    }

    @Override
    public Replaced replaceSecret(ActorId actorId, CredentialId credentialId, long expectedRevision, Draft draft) {
        String name = requireName(draft.name());
        String appId = requireAppId(draft.appId());
        byte[] secret = requireSecret(draft.appSecret());
        transactions.executeWithoutResult(_ -> {
            var stored = requireCredentialMutation(actorId, credentialId);
            if (stored.region() != draft.region() || !stored.appId().equals(appId))
                throw SourceException.conflict("Lark credential targets a different app");
            credentials.requireConfigured();
        });
        Verification verification;
        try (var credential = new LarkGateway.Credential(draft.region(), appId, secret)) {
            verification = verify(credential);
        }
        long revision = Objects.requireNonNull(transactions.execute(_ -> {
            TenantId tenantId = requireManagement(actorId);
            requireCredentialMutation(actorId, credentialId);
            long replaced = credentials.replaceSecret(tenantId, credentialId, expectedRevision, name, secret,
                    verification.bot());
            audit.record(AuditRecord.of(AuditAction.CREDENTIAL_UPDATE, tenantId).actor(actorId)
                    .resource("CREDENTIAL", credentialId.value(), name).detail("provider", "LARK")
                    .detail("change", "REPLACE_AUTHENTICATION").build());
            return replaced;
        }));
        Arrays.fill(secret, (byte) 0);
        return new Replaced(revision, verification.result());
    }

    @Override
    @Transactional
    public void rename(ActorId actorId, CredentialId credentialId, long expectedRevision, String name) {
        TenantId tenantId = requireManagement(actorId);
        requireCredentialMutation(actorId, credentialId);
        credentials.rename(tenantId, credentialId, expectedRevision, requireName(name));
        audit.record(AuditRecord.of(AuditAction.CREDENTIAL_UPDATE, tenantId).actor(actorId)
                .resource("CREDENTIAL", credentialId.value(), requireName(name)).detail("provider", "LARK")
                .detail("change", "RENAME").build());
    }

    @Override
    @Transactional
    public void delete(ActorId actorId, CredentialId credentialId, long expectedRevision) {
        TenantId tenantId = requireManagement(actorId);
        requireCredentialMutation(actorId, credentialId);
        credentials.delete(tenantId, credentialId, expectedRevision);
        audit.record(AuditRecord.of(AuditAction.CREDENTIAL_DELETE, tenantId).actor(actorId)
                .resource("CREDENTIAL", credentialId.value(), null).detail("provider", "LARK").build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialView> list(ActorId actorId) {
        var access = authorization.require(actorId, IamCapability.SOURCES_MANAGE, true);
        boolean global = access.authority() == Authority.GLOBAL;
        return credentials.list(access.tenantId(), global ? null : actorId).stream().map(view -> {
            var actions = new ArrayList<>(List.of("rename", "replace_secret", "test"));
            if (view.sourceCount() == 0) actions.add("delete");
            return new CredentialView(view.id(), view.name(), view.region(), view.appId(), view.status(),
                    view.botName(), view.credentialRevision(), view.createdAt(), view.updatedAt(),
                    view.sourceCount(), List.copyOf(actions));
        }).toList();
    }

    @Override
    public TestResult test(ActorId actorId, CredentialId credentialId) {
        var loaded = Objects.requireNonNull(transactions.execute(_ -> {
            TenantId tenant = requireManagement(actorId);
            var row = requireCredentialMutation(actorId, credentialId);
            if (!row.usable()) throw LarkException.needsUpdate();
            return new Loaded(tenant, row);
        }));
        TenantId tenantId = loaded.tenantId();
        var stored = loaded.stored();
        try (var credential = credentials.credential(tenantId, stored)) {
            Verification verification;
            try {
                verification = verify(credential);
            } catch (LarkException rejected) {
                if (LarkException.isCredentialRejection(rejected.code()))
                    transactions.executeWithoutResult(_ ->
                            credentials.markNeedsUpdate(tenantId, credentialId, stored.revision()));
                throw rejected;
            }
            transactions.executeWithoutResult(_ ->
                    credentials.recordBot(tenantId, credentialId, stored.revision(), verification.bot()));
            return verification.result();
        }
    }

    /**
     * Asks Lark for a tenant token, reads the bot the app is and probes the read scopes. A refused credential means
     * nothing is stored; a working one with scopes still to grant is stored, and names them.
     */
    private Verification verify(LarkGateway.Credential credential) {
        if (!verifications.tryAcquire()) throw LarkException.unavailable();
        try (var session = provider.open(credential)) {
            var bot = session.bot();
            return new Verification(bot, session.missingScopes());
        } catch (LarkProviderException exception) {
            throw switch (exception.failure()) {
                case AUTHENTICATION -> LarkException.rejected();
                case QUOTA -> LarkException.throttled();
                default -> LarkException.unavailable();
            };
        } finally {
            verifications.release();
        }
    }

    private TenantId requireManagement(ActorId actorId) {
        return authorization.lockAndRequireScopedMutation(Objects.requireNonNull(actorId), IamCapability.SOURCES_MANAGE).tenantId();
    }

    private JdbcLarkCredentialRepository.Stored requireCredentialMutation(ActorId actorId, CredentialId credentialId) {
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

    private static String requireAppId(String appId) {
        String trimmed = appId == null ? "" : appId.strip();
        if (!APP_ID.matcher(trimmed).matches()) throw LarkException.invalidAppId();
        return trimmed;
    }

    /** The App Secret as its bytes; four bytes at most encode one character. */
    private static byte[] requireSecret(byte[] secret) {
        if (secret.length == 0 || secret.length > MAX_SECRET_CHARS * 4) throw LarkException.invalidSecret();
        return secret;
    }

    private record Verification(LarkGateway.Bot bot, Set<String> missingScopes) {
        TestResult result() { return new TestResult(bot.appName(), missingScopes); }
    }

    private record Loaded(TenantId tenantId, JdbcLarkCredentialRepository.Stored stored) {}
}
