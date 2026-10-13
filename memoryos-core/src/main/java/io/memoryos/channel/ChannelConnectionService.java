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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Tenant's bots under {@link IamCapability#CHANNELS_MANAGE} (MEM-246). Adding a bot creates the service
 * account its group questions run as, owned by the bot; removing it retires that account, so what it did keeps its
 * name. A new bot starts switched off: it answers only once an administrator switches it on. Changes are audited.
 */
@Service
public class ChannelConnectionService {
    private static final Pattern LARK_APP_ID = Pattern.compile("^cli_[0-9a-z]{8,40}$");
    private static final Set<String> LANGUAGES = Set.of("vi", "en");
    private static final String SECRET_PURPOSE = "app-secret";

    private final IamAuthorization authorization;
    private final JdbcChannelConnectionRepository connections;
    private final ChannelCredentialConfiguration credentials;
    private final ServiceAccounts accounts;
    private final AuditTrail audit;

    public ChannelConnectionService(IamAuthorization authorization, JdbcChannelConnectionRepository connections,
                                    ChannelCredentialConfiguration credentials, ServiceAccounts accounts, AuditTrail audit) {
        this.authorization = authorization;
        this.connections = connections;
        this.credentials = credentials;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ChannelConnection> list(ActorId actor) {
        return connections.list(reader(actor));
    }

    @Transactional(readOnly = true)
    public ChannelConnection get(ActorId actor, UUID id) {
        return connections.find(reader(actor), id).orElseThrow(ChannelException::notFound);
    }

    @Transactional
    public ChannelConnection create(ActorId actor, ChannelConnectionDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        var tenant = writer(actor);
        String name = name(draft.name());
        String appId = appId(draft.provider(), draft.appId());
        String language = language(draft.noticeLanguage());
        Objects.requireNonNull(draft.region(), "region must not be null");
        byte[] secret = secret(draft.secret());
        if (connections.appInUse(draft.provider(), draft.region(), appId)) throw ChannelException.appInUse();
        UUID id = UUID.randomUUID();
        var envelope = credentials.cipher(draft.provider()).encrypt(tenant, id, SECRET_PURPOSE, secret);
        ActorId account = accounts.create(tenant, id, name, language);
        var created = connections.insert(tenant, id, draft.provider(), name, draft.region(), appId, envelope, language, account);
        audit.record(AuditRecord.of(AuditAction.CHANNEL_CREATE, tenant).actor(actor)
                .resource("CHANNEL", id, name).detail("provider", draft.provider().name()).detail("app", appId).build());
        return created;
    }

    @Transactional
    public ChannelConnection replaceSecret(ActorId actor, UUID id, long revision, String secret) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        var envelope = credentials.cipher(current.provider()).encrypt(tenant, id, SECRET_PURPOSE, secret(secret));
        var updated = connections.replaceSecret(tenant, id, revision, envelope).orElseThrow(ChannelException::changed);
        record(actor, tenant, updated, "secret");
        return updated;
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

    private static String appId(ChannelProvider provider, String appId) {
        Objects.requireNonNull(provider, "provider must not be null");
        String stripped = appId == null ? "" : appId.strip();
        if (provider == ChannelProvider.LARK && !LARK_APP_ID.matcher(stripped).matches()) {
            throw ChannelException.invalid("Supply the App ID of the Lark app, which starts with cli_.");
        }
        return stripped;
    }

    private static String language(String language) {
        if (language == null || !LANGUAGES.contains(language)) throw ChannelException.invalid("Choose Vietnamese or English for the bot's notices.");
        return language;
    }

    private static byte[] secret(String secret) {
        if (secret == null || secret.isBlank() || secret.length() > 256) {
            throw ChannelException.invalid("Supply the App Secret, between 1 and 256 characters.");
        }
        return secret.strip().getBytes(StandardCharsets.UTF_8);
    }
}
