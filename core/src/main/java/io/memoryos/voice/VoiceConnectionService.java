package io.memoryos.voice;


import io.memoryos.audit.AuditAction;
import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.voice.persistence.VoiceConnectionEntity;
import io.memoryos.voice.persistence.VoiceConnectionRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized voice configuration only. Provider requests happen after these transactions return. */
@Service
public class VoiceConnectionService {
    private static final int MAX_IDENTIFIER = 200;
    private static final int MAX_CREDENTIAL = 8192;
    private final VoiceConnectionRepository connections;
    private final ProviderConnections admin;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;

    public VoiceConnectionService(VoiceConnectionRepository connections, ProviderConnections admin,
                                  IamAuthorization authorization, TenantAccessResolver tenants) {
        this.connections = connections; this.admin = admin;
        this.authorization = authorization; this.tenants = tenants;
    }

    public record View(VoiceProvider provider, String endpoint, String sttModel, String ttsModel, String ttsVoice,
                       boolean credentialConfigured, boolean sttActive, boolean ttsActive, long revision) {}
    /** {@code activate} selects the new connection for one function, as connecting from an Onyx voice card does. */
    public record Input(String endpoint, String sttModel, String ttsModel, String ttsVoice,
                        ProviderCredentials.Change credential, @Nullable VoiceFunction activate, long revision) {
        @Override public @NonNull String toString() { return "VoiceConnectionInput[redacted]"; }
    }
    public record Connection(UUID id, UUID tenantId, VoiceProvider provider, String endpoint, String sttModel,
                             String ttsModel, String ttsVoice, @Nullable String encryptedCredential, long revision) {
        @Override public @NonNull String toString() { return "VoiceConnection[redacted]"; }
    }
    /** The endpoint and plaintext credential of one provider request; it never leaves the request that uses it. */
    public record Probe(VoiceProvider provider, String baseUrl, String key) {
        @Override public @NonNull String toString() { return "VoiceProbe[redacted]"; }
    }
    public record Access(@Nullable Connection stt, @Nullable Connection tts) {}

    @Transactional(readOnly = true)
    public void requireManager(ActorId actor) {
        authorization.require(actor, IamCapability.MODELS_MANAGE, false);
    }

    @Transactional(readOnly = true)
    public List<View> list(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        return connections.findByTenantIdOrderByProvider(tenant).stream().map(this::view).toList();
    }

    /** Resolves a draft's effective endpoint and credential so it can be verified before anything is stored. */
    @Transactional(readOnly = true)
    public Probe probe(ActorId actor, VoiceProvider provider, Input input) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        validate(provider, input);
        input = trimmed(input);
        var existing = connections.findByTenantIdAndProvider(tenant, provider);
        String key = switch (input.credential().action()) {
            case REPLACE -> input.credential().value();
            case REMOVE -> "";
            case KEEP -> existing.map(c -> admin.key(tenant, c.id(), c.credential())).orElse("");
        };
        return new Probe(provider, provider.baseUrl(input.endpoint()), key);
    }

    @Transactional
    public View save(ActorId actor, VoiceProvider provider, Input input) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        validate(provider, input);
        input = trimmed(input);
        var existing = connections.findByTenantIdAndProvider(tenant, provider);
        var entity = existing.orElseGet(() -> new VoiceConnectionEntity(tenant, provider));
        String credential = admin.reconfigure(tenant, entity.id(), entity.revision(), input.revision(),
                entity.credential(), input.credential(), VoiceException::conflict);
        entity.configure(input.endpoint(), input.sttModel(), input.ttsModel(), input.ttsVoice(), credential);
        if (!serves(entity, VoiceFunction.STT)) entity.selectStt(false);
        if (!serves(entity, VoiceFunction.TTS)) entity.selectTts(false);
        var saved = connections.saveAndFlush(entity);
        if (existing.isEmpty() && input.activate() != null) {
            if (!serves(saved, input.activate()))
                throw VoiceException.invalid("This connection cannot serve the selected voice function.");
            activate(tenant, saved, input.activate());
        }
        audit(tenant, actor, provider, "CONFIGURE", ProviderConnections.credentialChange(input.credential()));
        return view(saved);
    }

    /** Disconnecting removes the provider from both functions, as in Onyx. */
    @Transactional
    public void delete(ActorId actor, VoiceProvider provider, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var entity = connections.findByTenantIdAndProvider(tenant, provider).orElseThrow(VoiceException::unavailable);
        if (entity.revision() != revision) throw VoiceException.conflict();
        connections.delete(entity);
        audit(tenant, actor, provider, "DISCONNECT", ProviderCredentials.Action.REMOVE.name());
    }

    /** A null provider turns the function off for the Tenant. A Text-to-Speech selection may choose the provider's model. */
    @Transactional
    public void select(ActorId actor, VoiceFunction function, @Nullable VoiceProvider provider, @Nullable String model) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        if (function == null) throw VoiceException.invalid("A voice function is required.");
        if (model != null && (function != VoiceFunction.TTS || provider == null || model.isBlank() || !validIdentifier(model)))
            throw VoiceException.invalid("Only a selected Text-to-Speech provider accepts a model.");
        if (provider == null) {
            for (var connection : connections.findByTenantIdOrderByProvider(tenant)) select(connection, function, false);
            audit(tenant, actor, null, "DISABLE_" + function.name(), null);
            return;
        }
        if (function == VoiceFunction.TTS && !provider.speech())
            throw VoiceException.invalid("This provider does not read text aloud.");
        var selected = connections.findByTenantIdAndProvider(tenant, provider).orElseThrow(VoiceException::unavailable);
        if (model != null) selected.useTtsModel(model);
        if (!serves(selected, function)) throw VoiceException.providerUnavailable();
        activate(tenant, selected, function);
        audit(tenant, actor, provider, "SELECT_" + function.name(), null);
    }

    @Transactional(readOnly = true)
    public Probe forTest(ActorId actor, VoiceProvider provider) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        var connection = connections.findByTenantIdAndProvider(tenant, provider).orElseThrow(VoiceException::unavailable);
        if (!usable(connection)) throw VoiceException.providerUnavailable();
        return new Probe(provider, provider.baseUrl(connection.endpoint()),
                admin.key(tenant, connection.id(), connection.credential()));
    }

    @Transactional(readOnly = true)
    public Access resolve(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(VoiceException::unavailable).value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        return new Access(active(all, VoiceFunction.STT), active(all, VoiceFunction.TTS));
    }

    /**
     * Every connection that can transcribe, with the Tenant's selected one first. Membership is enough to read it: a
     * member choosing which provider transcribes their own recording must see what the Tenant configured, and the
     * listing carries no endpoint or credential.
     */
    @Transactional(readOnly = true)
    public List<Connection> transcribers(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(VoiceException::unavailable).value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        var selected = active(all, VoiceFunction.STT);
        return all.stream().filter(c -> serves(c, VoiceFunction.STT)).map(this::snapshot)
                .sorted(java.util.Comparator.comparing(c -> selected != null && c.id().equals(selected.id()) ? 0 : 1))
                .toList();
    }

    /** The connection a member asked to transcribe with, or the Tenant's selected one when they named none. */
    @Transactional(readOnly = true)
    public Connection transcriber(ActorId actor, @Nullable VoiceProvider provider) {
        if (provider == null) {
            var stt = resolve(actor).stt();
            if (stt == null) throw VoiceException.providerUnavailable();
            return stt;
        }
        return transcribers(actor).stream().filter(c -> c.provider() == provider).findFirst()
                .orElseThrow(VoiceException::providerUnavailable);
    }

    public String key(Connection connection) {
        return admin.key(connection.tenantId(), connection.id(), connection.encryptedCredential());
    }

    private void audit(UUID tenant, ActorId actor, @Nullable VoiceProvider provider, String change,
            @Nullable String credential) {
        admin.audit(AuditAction.VOICE_CONNECTION_CHANGE, "VOICE_CONNECTION", tenant, actor,
                provider == null ? null : provider.name(), change, credential);
    }

    private void activate(UUID tenant, VoiceConnectionEntity selected, VoiceFunction function) {
        ProviderConnections.selectOnly(connections.findByTenantIdOrderByProvider(tenant), selected,
                (connection, active) -> select(connection, function, active), connections::flush);
        connections.flush(); // Return the advanced revision to the caller.
    }

    private @Nullable Connection active(List<VoiceConnectionEntity> all, VoiceFunction function) {
        return all.stream().filter(c -> (function == VoiceFunction.STT ? c.sttActive() : c.ttsActive()) && serves(c, function))
                .findFirst().map(this::snapshot).orElse(null);
    }

    private static void select(VoiceConnectionEntity connection, VoiceFunction function, boolean active) {
        if (function == VoiceFunction.STT) connection.selectStt(active); else connection.selectTts(active);
    }

    private boolean usable(VoiceConnectionEntity c) {
        return admin.usable(c.provider().requiresKey(), c.credential());
    }

    private boolean serves(VoiceConnectionEntity c, VoiceFunction function) {
        return usable(c) && (function == VoiceFunction.STT ? !c.sttModel().isEmpty() : !c.ttsModel().isEmpty() && !c.ttsVoice().isEmpty());
    }

    private static void validate(VoiceProvider provider, Input input) {
        if (provider == null || input == null || input.endpoint() == null || input.credential() == null
                || input.credential().action() == null || !validIdentifier(input.sttModel())
                || !validIdentifier(input.ttsModel()) || !validIdentifier(input.ttsVoice()))
            throw VoiceException.invalid("Invalid voice connection.");
        var credential = input.credential();
        boolean replace = credential.action() == ProviderCredentials.Action.REPLACE;
        if (replace ? credential.value() == null || credential.value().isBlank() || credential.value().length() > MAX_CREDENTIAL
                : credential.value() != null)
            throw VoiceException.invalid("Invalid provider credential.");
        if (!provider.speech() && (!input.ttsModel().isEmpty() || !input.ttsVoice().isEmpty()))
            throw VoiceException.invalid("This provider does not read text aloud.");
        ProviderConnections.checkEndpoint(input.endpoint(), provider.requiresEndpoint(),
                () -> VoiceException.invalid("This provider requires its own endpoint."));
    }

    /**
     * A key arrives from a clipboard and often brings a newline with it. An HTTP header ignores that, so the
     * saved-connection check passes, while Soniox reads its key from a JSON field and answers
     * {@code error_code 401 unauthenticated} — a key that verifies and then cannot transcribe. It is stripped once,
     * here, before anything stores or probes it.
     */
    static Input trimmed(Input input) {
        var credential = input.credential();
        if (credential.action() != ProviderCredentials.Action.REPLACE || credential.value() == null) return input;
        String key = credential.value().strip();
        if (key.equals(credential.value())) return input;
        return new Input(input.endpoint(), input.sttModel(), input.ttsModel(), input.ttsVoice(),
                new ProviderCredentials.Change(credential.action(), key), input.activate(), input.revision());
    }

    private static boolean validIdentifier(@Nullable String value) {
        return value != null && value.length() <= MAX_IDENTIFIER && value.equals(value.strip());
    }

    private View view(VoiceConnectionEntity c) {
        return new View(c.provider(), c.endpoint(), c.sttModel(), c.ttsModel(), c.ttsVoice(),
                admin.configured(c.credential()), c.sttActive(), c.ttsActive(), c.revision());
    }

    private Connection snapshot(VoiceConnectionEntity c) {
        return new Connection(c.id(), c.tenantId(), c.provider(), c.endpoint(), c.sttModel(), c.ttsModel(), c.ttsVoice(),
                c.credential(), c.revision());
    }
}
