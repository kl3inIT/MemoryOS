package io.memoryos.channel;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.chat.ChatChannelBindings;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatPersonaService;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelGroupRepository;
import io.memoryos.channel.persistence.JdbcChannelLeaseRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.ServiceAccounts;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
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
    private final ChatPersonaService personas;
    private final ChatChannelBindings bindings;
    private final JdbcChannelGroupRepository groups;
    private final JdbcChannelLeaseRepository leases;
    private final Semaphore verifications = new Semaphore(CONCURRENT_VERIFICATIONS);

    public ChannelConnectionService(IamAuthorization authorization, JdbcChannelConnectionRepository connections,
                                    ChannelCredentialConfiguration credentials, ServiceAccounts accounts, AuditTrail audit,
                                    ChannelAdapters adapters, PlatformTransactionManager transactionManager,
                                    ChatPersonaService personas, ChatChannelBindings bindings,
                                    JdbcChannelGroupRepository groups, JdbcChannelLeaseRepository leases) {
        this.authorization = authorization;
        this.connections = connections;
        this.credentials = credentials;
        this.accounts = accounts;
        this.audit = audit;
        this.adapters = adapters;
        this.transactions = new TransactionTemplate(transactionManager);
        this.personas = personas;
        this.bindings = bindings;
        this.groups = groups;
        this.leases = leases;
    }

    /** The Tenant's bots with what the process that holds them last wrote of each. */
    @Transactional(readOnly = true)
    public List<Listed> listWithState(ActorId actor) {
        var tenant = reader(actor);
        var states = connections.states(tenant);
        Instant now = Instant.now();
        var bound = bindings.all(tenant);
        var names = personas.names(actor, bound.values());
        return connections.list(tenant).stream().map(connection -> {
            var state = states.getOrDefault(connection.id(), ChannelConnectionState.NONE);
            UUID assistant = bound.get(connection.id());
            String name = assistant == null ? null : names.get(assistant);
            return new Listed(connection, state, status(connection, state, now), name == null ? null : assistant, name);
        }).toList();
    }

    /**
     * What the administrator is shown of a bot: switched off, held by no process, showing a code to scan, open,
     * failing, or opening.
     */
    static Listed.Status status(ChannelConnection connection, ChannelConnectionState state, Instant now) {
        if (!connection.enabled()) return Listed.Status.OFF;
        // A supervisor writes every pass; three passes missed mean no process holds the bot.
        if (state.heartbeatAt() == null || state.heartbeatAt().plus(ChannelSupervisor.PASS.multipliedBy(3)).isBefore(now)) {
            return Listed.Status.NOT_RUNNING;
        }
        if (state.pairing()) return Listed.Status.PAIRING;
        if (state.openedAt() != null) return Listed.Status.CONNECTED;
        return state.failureCode() != null ? Listed.Status.FAILED : Listed.Status.CONNECTING;
    }

    /** Checks an app with its platform without storing anything. */
    public ChannelVerification test(ActorId actor, ChannelProvider provider, ChannelCredentials credentials) {
        Objects.requireNonNull(provider, "provider must not be null");
        var given = given(credentials, adapters.of(provider).features().pairs());
        transactions.executeWithoutResult(_ -> reader(actor));
        return verify(provider, given);
    }

    /**
     * {@code assistantId} and {@code assistant} are the assistant the bot answers with, both null when it answers
     * with the built-in one.
     */
    public record Listed(ChannelConnection connection, ChannelConnectionState state, Status status,
                         @Nullable UUID assistantId, @Nullable String assistant) {
        public enum Status { OFF, NOT_RUNNING, CONNECTING, PAIRING, CONNECTED, FAILED }
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
        var adapter = adapters.of(draft.provider());
        var asked = given(draft.credentials(), adapter.features().pairs());
        var given = new ChannelCredentials(asked.appId(), asked.secret(), adapter.settings(asked.settings()));
        boolean pairs = adapter.features().pairs();
        transactions.executeWithoutResult(_ -> {
            var tenant = writer(actor);
            credentials.cipher(draft.provider());
            if (given.appId() != null && inUse(tenant, draft.provider(), given.appId(), pairs)) {
                throw ChannelException.appInUse();
            }
        });
        var verification = verify(draft.provider(), given);
        String appId = verification.appId();
        return Objects.requireNonNull(transactions.execute(_ -> {
            var tenant = writer(actor);
            if (inUse(tenant, draft.provider(), appId, pairs)) throw ChannelException.appInUse();
            UUID id = UUID.randomUUID();
            // A bot linked by a code keeps no secret; its session is the provider bridge's (MEM-283).
            var envelope = pairs ? null : credentials.cipher(draft.provider()).encrypt(tenant, id,
                    SECRET_PURPOSE, given.secret().getBytes(StandardCharsets.UTF_8));
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
            if (adapters.of(found.provider()).features().pairs()) {
                throw ChannelException.invalid("This bot has no secret: link it again instead.");
            }
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

    /**
     * Links a bot of a provider that pairs again (MEM-283): the revision moves on, so the process that holds it opens
     * it again, out of a failure that waits for a change, and shows a new code while the number has no session.
     */
    @Transactional
    public ChannelConnection relink(ActorId actor, UUID id, long revision) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        if (current.revision() != revision) throw ChannelException.changed();
        if (!adapters.of(current.provider()).features().pairs()) throw ChannelException.invalid("This bot is not linked by a code.");
        var updated = connections.updateSettings(tenant, id, revision, current.settings())
                .orElseThrow(() -> missingOrChanged(tenant, id));
        record(actor, tenant, updated, "relink");
        return updated;
    }

    /** The code a bot being linked shows now (MEM-283); empty when it shows none. */
    @Transactional(readOnly = true)
    public Optional<ChannelPairing> pairing(ActorId actor, UUID id) {
        var tenant = reader(actor);
        connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        return leases.pairing(tenant, id, Instant.now());
    }

    /**
     * Whether the app already serves a bot: of any Tenant, as the platform vouches for it; of this Tenant for a bot linked
     * by a code, whose number nothing vouches for until it is linked, so that no Tenant holds another's number by adding
     * it first (MEM-283). Linking lets a number serve one bot.
     */
    private boolean inUse(TenantId tenant, ChannelProvider provider, String appId, boolean pairs) {
        return pairs ? connections.appInUse(tenant, provider, appId) : connections.appInUse(provider, appId);
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

    /**
     * Makes a bot answer anyone who addresses it, from its assistant's attachments only, or only the Tenant's people
     * again (MEM-298). Making it public decides who may ask its assistants, as binding does, so it takes edit rights on
     * the bot's assistant and on each of its groups' own, the built-in one aside. The revision moves on, so the process
     * that holds the bot opens it again; conversations are continued only in the mode they were started in, so each one
     * starts over.
     */
    @Transactional
    public ChannelConnection setPublic(ActorId actor, UUID id, long revision, boolean publicAccess) {
        var tenant = writer(actor);
        connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        if (publicAccess) {
            bindings.bound(tenant, id).ifPresent(assistant -> requireEditable(actor, assistant));
            bindings.groups(tenant, id).values().forEach(assistant -> requireEditable(actor, assistant));
        }
        var updated = connections.setPublic(tenant, id, revision, publicAccess).orElseThrow(() -> missingOrChanged(tenant, id));
        record(actor, tenant, updated, publicAccess ? "public" : "private");
        return updated;
    }

    /**
     * Changes some of a bot's public settings, such as how a Zalo bot receives; the provider checks the result. The
     * revision moves on, so the process that holds the bot opens it again with them.
     */
    @Transactional
    public ChannelConnection updateSettings(ActorId actor, UUID id, long revision, Map<String, String> changes) {
        Objects.requireNonNull(changes, "changes must not be null");
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        if (current.revision() != revision) throw ChannelException.changed();
        var merged = new HashMap<>(current.settings());
        merged.putAll(changes);
        var settings = adapters.of(current.provider()).settings(merged);
        var updated = connections.updateSettings(tenant, id, revision, settings).orElseThrow(() -> missingOrChanged(tenant, id));
        record(actor, tenant, updated, "settings");
        return updated;
    }

    /**
     * Binds a bot to an assistant, or to none so it answers with the built-in one (MEM-273). Binding decides who may ask
     * an assistant, so it takes the capability to manage bots and edit rights on the assistant the bot goes to and on
     * the one it leaves while that one exists; a bot bound elsewhere moves in the same step. The built-in assistant is
     * no binding: choosing it unbinds. The bot's revision stays, and conversations held with the assistant it left no
     * longer use it.
     */
    @Transactional
    public ChannelConnection bindPersona(ActorId actor, UUID id, @Nullable UUID personaId) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        UUID target = null;
        if (personaId != null) {
            var assistant = personas.get(actor, personaId);
            if (!assistant.builtin()) {
                if (!assistant.permissions().edit()) throw ChannelException.assistantNotEditable();
                target = assistant.id();
            }
        }
        UUID leaving = bindings.bound(tenant, id).orElse(null);
        if (Objects.equals(target, leaving)) return current;
        requireMayLeave(actor, leaving);
        bindings.bind(tenant, id, target);
        record(actor, tenant, current, target == null ? "assistant-removed" : "assistant");
        return current;
    }

    /**
     * Where an assistant answers, for whoever may use it (MEM-273): the bots it is the default of and each bot's group
     * chats given it. Its page lists them read-only.
     */
    @Transactional(readOnly = true)
    public List<Answering> boundTo(ActorId actor, UUID personaId) {
        var tenant = authorization.require(actor, IamCapability.CHAT_READ, false).tenantId();
        personas.get(actor, personaId);
        var bound = bindings.all(tenant);
        var grouped = bindings.groupsBoundTo(tenant, personaId);
        return connections.list(tenant).stream()
                .filter(bot -> personaId.equals(bound.get(bot.id())) || grouped.containsKey(bot.id()))
                .map(bot -> {
                    var chats = grouped.getOrDefault(bot.id(), List.of());
                    var known = groups.list(tenant, bot.id()).stream()
                            .filter(group -> chats.contains(group.chatId())).toList();
                    return new Answering(bot, personaId.equals(bound.get(bot.id())), known);
                }).toList();
    }

    /** A bot an assistant answers with: by default, and in which of its group chats. */
    public record Answering(ChannelConnection connection, boolean byDefault, List<ChannelGroup> groups) {}

    /**
     * A group chat of a bot with the assistant it is given, both null when it answers with the bot's default, and
     * whether the bot answers there only once an administrator allows it (MEM-285).
     */
    public record Group(ChannelGroup group, @Nullable UUID assistantId, @Nullable String assistant,
                        boolean needsPermission) {}

    /**
     * The group chats a bot is known in, each with its own assistant: those waiting for permission first, then the most
     * recently asked.
     */
    @Transactional(readOnly = true)
    public List<Group> groups(ActorId actor, UUID id) {
        var tenant = reader(actor);
        var bot = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        boolean needsPermission = adapters.of(bot.provider()).features().groupsNeedPermission();
        var given = bindings.groups(tenant, id);
        var names = personas.names(actor, given.values());
        return groups.list(tenant, id).stream().map(group -> {
            UUID assistant = given.get(group.chatId());
            String name = assistant == null ? null : names.get(assistant);
            return new Group(group, name == null ? null : assistant, name, needsPermission);
        }).sorted(Comparator.comparing(group -> !group.needsPermission() || group.group().allowedAt() != null)).toList();
    }

    /**
     * Allows a bot to answer in a group chat, or withdraws it (MEM-285). Only a group of a provider that can tell
     * neither an outsider nor who is in it needs it; the next question there is answered or refused accordingly.
     */
    @Transactional
    public void allowGroup(ActorId actor, UUID id, String chatId, boolean allowed) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        if (!adapters.of(current.provider()).features().groupsNeedPermission()) {
            throw ChannelException.invalid("This bot answers in a group without an administrator's permission.");
        }
        var group = groups.find(tenant, id, chatId).orElseThrow(ChannelException::groupNotFound);
        if ((group.allowedAt() != null) == allowed) return;
        if (!groups.allow(tenant, id, chatId, allowed ? Instant.now() : null)) throw ChannelException.groupNotFound();
        record(actor, tenant, current, allowed ? "group-allowed" : "group-disallowed", chatId);
    }

    /**
     * Gives a group chat of a bot an assistant of its own, or none so it answers with the bot's default (MEM-273). It
     * decides who may ask the assistant, so it takes the capability to manage bots and edit rights on the assistant
     * the group goes to and on the one it leaves while that one exists; the built-in assistant is everyone's and takes
     * no edit rights. Conversations held with the assistant it left no longer use it, and the group's conversation
     * starts over.
     */
    @Transactional
    public void bindGroupPersona(ActorId actor, UUID id, String chatId, @Nullable UUID personaId) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        groups.find(tenant, id, chatId).orElseThrow(ChannelException::groupNotFound);
        UUID target = null;
        if (personaId != null) {
            var assistant = personas.get(actor, personaId);
            if (!assistant.builtin() && !assistant.permissions().edit()) throw ChannelException.assistantNotEditable();
            target = assistant.id();
        }
        UUID leaving = bindings.groupBound(tenant, id, chatId).orElse(null);
        if (Objects.equals(target, leaving)) return;
        requireMayLeave(actor, leaving);
        bindings.bindGroup(tenant, id, chatId, target);
        record(actor, tenant, current, target == null ? "group-assistant-removed" : "group-assistant", chatId);
    }

    /** Names a group chat for the bot's page, or clears the name with a blank one. */
    @Transactional
    public void labelGroup(ActorId actor, UUID id, String chatId, @Nullable String label) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        String given = label == null || label.isBlank() ? null : label.strip();
        if (given != null && given.length() > 100) throw ChannelException.invalid("Name the group in up to 100 characters.");
        if (!groups.label(tenant, id, chatId, given)) throw ChannelException.groupNotFound();
        record(actor, tenant, current, "group-label", chatId);
    }

    /**
     * Forgets a group chat and the assistant it was given; it comes back when the bot next answers there. Taking the
     * group's assistant away takes edit rights on it while it exists, as {@link #bindGroupPersona} does.
     */
    @Transactional
    public void forgetGroup(ActorId actor, UUID id, String chatId) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        groups.find(tenant, id, chatId).orElseThrow(ChannelException::groupNotFound);
        requireMayLeave(actor, bindings.groupBound(tenant, id, chatId).orElse(null));
        if (!groups.forget(tenant, id, chatId)) throw ChannelException.groupNotFound();
        bindings.bindGroup(tenant, id, chatId, null);
        record(actor, tenant, current, "group-forgotten", chatId);
    }

    @Transactional
    public void delete(ActorId actor, UUID id, long revision) {
        var tenant = writer(actor);
        var current = connections.find(tenant, id).orElseThrow(ChannelException::notFound);
        if (!connections.delete(tenant, id, revision)) throw ChannelException.changed();
        bindings.release(tenant, id);
        accounts.retire(tenant, current.serviceAccount());
        audit.record(AuditRecord.of(AuditAction.CHANNEL_DELETE, tenant).actor(actor)
                .resource("CHANNEL", id, current.name()).detail("provider", current.provider().name()).build());
    }

    /**
     * A bot or group may leave the assistant it answers with only for someone who may edit it: the built-in one and an
     * assistant the actor can no longer see take none.
     */
    private void requireMayLeave(ActorId actor, @Nullable UUID leaving) {
        if (leaving == null || !personas.names(actor, List.of(leaving)).containsKey(leaving)) return;
        var left = personas.get(actor, leaving);
        if (!left.builtin() && !left.permissions().edit()) throw ChannelException.assistantNotEditable();
    }

    /**
     * An assistant a bot may answer anyone with only for someone who may edit it. A deleted one answers as none, so it
     * takes nothing; a live one the actor cannot see is refused like one they cannot edit.
     */
    private void requireEditable(ActorId actor, UUID assistant) {
        if (!personas.names(actor, List.of(assistant)).containsKey(assistant)) return;
        boolean editable;
        try {
            editable = personas.get(actor, assistant).permissions().edit();
        } catch (ChatException unavailable) {
            editable = false;
        }
        if (!editable) throw ChannelException.assistantNotEditable();
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

    private void record(ActorId actor, TenantId tenant, ChannelConnection connection, String change, String chatId) {
        audit.record(AuditRecord.of(AuditAction.CHANNEL_UPDATE, tenant).actor(actor)
                .resource("CHANNEL", connection.id(), connection.name()).detail("change", change)
                .detail("group", chatId).build());
    }

    private static String name(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > 100) throw ChannelException.invalid("Name the bot in 1 to 100 characters.");
        return stripped;
    }

    /**
     * The credentials as given, trimmed; whether they are the provider's shape is its adapter's to say. A provider that
     * pairs takes no secret, and its bot keeps none.
     */
    private static ChannelCredentials given(ChannelCredentials credentials, boolean pairs) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        String appId = credentials.appId() == null || credentials.appId().isBlank() ? null : credentials.appId().strip();
        if (pairs && !credentials.secret().isEmpty()) throw ChannelException.invalid("This bot takes no secret.");
        return new ChannelCredentials(appId, pairs ? "" : secretText(credentials.secret()), credentials.settings());
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
