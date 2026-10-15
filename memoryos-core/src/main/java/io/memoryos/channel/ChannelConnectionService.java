package io.memoryos.channel;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.ServiceAccounts;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Tenant's bots under {@link IamCapability#CHANNELS_MANAGE} (MEM-246). Adding a bot creates the service
 * account its group questions run as, owned by the bot; removing it retires that account, so what it did keeps its
 * name. A new bot starts switched off: it answers only once an administrator switches it on. Changes are audited.
 */
@Service
public class ChannelConnectionService {
    private static final Set<String> LANGUAGES = Set.of("vi", "en");
    private static final String SECRET_PURPOSE = "app-secret";
    private static final int CONCURRENT_VERIFICATIONS = 2;

    private final IamAuthorization authorization;
    private final JdbcChannelConnectionRepository connections;
    private final ChannelCredentialConfiguration credentials;
    private final ServiceAccounts accounts;
    private final AuditTrail audit;
    private final ChannelAdapters adapters;
    private final TransactionTemplate transactions;
    private final Semaphore verifications = new Semaphore(CONCURRENT_VERIFICATIONS);

    public ChannelConnectionService(IamAuthorization authorization, JdbcChannelConnectionRepository connections,
                                    ChannelCredentialConfiguration credentials, ServiceAccounts accounts, AuditTrail audit,
                                    ChannelAdapters adapters, PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.connections = connections;
        this.credentials = credentials;
        this.accounts = accounts;
        this.audit = audit;
        this.adapters = adapters;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The Tenant's bots with what the process that holds them last wrote of each. */
    @Transactional(readOnly = true)
    public List<Listed> listWithState(ActorId actor) {
        var tenant = reader(actor);
        var states = connections.states(tenant);
        Instant now = Instant.now();
        return connections.list(tenant).stream().map(connection -> {
            var state = states.getOrDefault(connection.id(), ChannelConnectionState.NONE);
            return new Listed(connection, state, status(connection, state, now));
        }).toList();
    }

    /** What the administrator is shown of a bot: switched off, held by no process, open, failing, or opening. */
    static Listed.Status status(ChannelConnection connection, ChannelConnectionState state, Instant now) {
        if (!connection.enabled()) return Listed.Status.OFF;
        // A supervisor writes every pass; three passes missed mean no process holds the bot.
        if (state.heartbeatAt() == null || state.heartbeatAt().plus(ChannelSupervisor.PASS.multipliedBy(3)).isBefore(now)) {
            return Listed.Status.NOT_RUNNING;
        }
        if (state.openedAt() != null) return Listed.Status.CONNECTED;
        return state.failureCode() != null ? Listed.Status.FAILED : Listed.Status.CONNECTING;
    }

    /** Checks an app with its platform without storing anything. */
    public ChannelVerification test(ActorId actor, ChannelProvider provider, ChannelCredentials credentials) {
        Objects.requireNonNull(provider, "provider must not be null");
        var given = given(credentials);
        transactions.executeWithoutResult(_ -> reader(actor));
        return verify(provider, given);
    }

    public record Listed(ChannelConnection connection, ChannelConnectionState state, Status status) {
        public enum Status { OFF, NOT_RUNNING, CONNECTING, CONNECTED, FAILED }
    }

    @Transactional(readOnly = true)
    public List<ChannelConnection> list(ActorId actor) {
        return connections.list(reader(actor));
    }

    @Transactional(readOnly = true)
    public ChannelConnection get(ActorId actor, UUID id) {
        return connections.find(reader(actor), id).orElseThrow(ChannelException::notFound);
    }

    /**
     * Adds a bot once its platform accepts the app. The platform is asked between two transactions, so no lock and no
     * connection is held while it answers; the checks are repeated in the one that writes.
     */
    public ChannelConnection create(ActorId actor, ChannelConnectionDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(draft.provider(), "provider must not be null");
        String name = name(draft.name());
        String language = language(draft.noticeLanguage());
        var given = given(draft.credentials());
        transactions.executeWithoutResult(_ -> {
            writer(actor);
            credentials.cipher(draft.provider());
            if (given.appId() != null && connections.appInUse(draft.provider(), given.appId())) {
                throw ChannelException.appInUse();
            }
        });
        var verification = verify(draft.provider(), given);
        String appId = verification.appId();
        return Objects.requireNonNull(transactions.execute(_ -> {
            var tenant = writer(actor);
            if (connections.appInUse(draft.provider(), appId)) throw ChannelException.appInUse();
            UUID id = UUID.randomUUID();
            var envelope = credentials.cipher(draft.provider()).encrypt(tenant, id, SECRET_PURPOSE,
                    given.secret().getBytes(StandardCharsets.UTF_8));
            ActorId account = accounts.create(tenant, id, name, language);
            connections.insert(tenant, id, draft.provider(), name, appId, given.settings(), envelope, language, account);
            connections.recordVerification(tenant, id, verification);
            audit.record(AuditRecord.of(AuditAction.CHANNEL_CREATE, tenant).actor(actor)
                    .resource("CHANNEL", id, name).detail("provider", draft.provider().name()).detail("app", appId).build());
            return connections.find(tenant, id).orElseThrow();
        }));
    }

    /** Replaces a bot's secret once its platform accepts it, asked between two transactions as when it is added. */
    public ChannelConnection replaceSecret(ActorId actor, UUID id, long revision, String secret) {
        String plain = secretText(secret);
        var current = Objects.requireNonNull(transactions.execute(_ -> {
            var tenant = writer(actor);
            var found = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
            if (found.revision() != revision) throw ChannelException.changed();
            credentials.cipher(found.provider());
            return found;
        }));
        var verification = verify(current.provider(), new ChannelCredentials(current.appId(), plain, current.settings()));
        // A secret of another app, as a Zalo token of another bot is, does not replace this one's.
        if (!verification.appId().equals(current.appId())) throw ChannelException.rejected();
        return Objects.requireNonNull(transactions.execute(_ -> {
            var tenant = writer(actor);
            var envelope = credentials.cipher(current.provider()).encrypt(tenant, id, SECRET_PURPOSE,
                    plain.getBytes(StandardCharsets.UTF_8));
            connections.replaceSecret(tenant, id, revision, envelope).orElseThrow(() -> missingOrChanged(tenant, id));
            connections.recordVerification(tenant, id, verification);
            var updated = connections.find(tenant, id).orElseThrow();
            record(actor, tenant, updated, "secret");
            return updated;
        }));
    }

    /** At most a few checks at once, so a burst of them cannot hold the platform's connections. */
    private ChannelVerification verify(ChannelProvider provider, ChannelCredentials credentials) {
        if (!verifications.tryAcquire()) throw ChannelException.providerUnavailable();
        try {
            return adapters.of(provider).verify(credentials);
        } finally {
            verifications.release();
        }
    }

    @Transactional
    public ChannelConnection setEnabled(ActorId actor, UUID id, long revision, boolean enabled) {
        var tenant = writer(actor);
        var updated = connections.setEnabled(tenant, id, revision, enabled).orElseThrow(() -> missingOrChanged(tenant, id));
        record(actor, tenant, updated, enabled ? "enabled" : "disabled");
        return updated;
    }

    @Transactional
    public void delete(ActorId actor, UUID id, long revision) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        if (!connections.delete(tenant, id, revision)) throw ChannelException.changed();
        accounts.retire(tenant, current.serviceAccount());
        audit.record(AuditRecord.of(AuditAction.CHANNEL_DELETE, tenant).actor(actor)
                .resource("CHANNEL", id, current.name()).detail("provider", current.provider().name()).build());
    }

    private TenantId reader(ActorId actor) {
        return authorization.require(actor, IamCapability.CHANNELS_MANAGE, false).tenantId();
    }

    private TenantId writer(ActorId actor) {
        return authorization.lockAndRequireExclusive(actor, IamCapability.CHANNELS_MANAGE).tenantId();
    }

    private ChannelException missingOrChanged(TenantId tenant, UUID id) {
        return connections.find(tenant, id).isPresent() ? ChannelException.changed() : ChannelException.notFound();
    }

    private void record(ActorId actor, TenantId tenant, ChannelConnection connection, String change) {
        audit.record(AuditRecord.of(AuditAction.CHANNEL_UPDATE, tenant).actor(actor)
                .resource("CHANNEL", connection.id(), connection.name()).detail("change", change).build());
    }

    private static String name(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > 100) throw ChannelException.invalid("Name the bot in 1 to 100 characters.");
        return stripped;
    }

    /** The credentials as given, trimmed; whether they are the provider's shape is its adapter's to say. */
    private static ChannelCredentials given(ChannelCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        String appId = credentials.appId() == null || credentials.appId().isBlank() ? null : credentials.appId().strip();
        return new ChannelCredentials(appId, secretText(credentials.secret()), credentials.settings());
    }

    private static String language(String language) {
        if (language == null || !LANGUAGES.contains(language)) throw ChannelException.invalid("Choose Vietnamese or English for the bot's notices.");
        return language;
    }

    private static String secretText(String secret) {
        if (secret == null || secret.isBlank() || secret.length() > 256) {
            throw ChannelException.invalid("Supply the App Secret, between 1 and 256 characters.");
        }
        return secret.strip();
    }
}
