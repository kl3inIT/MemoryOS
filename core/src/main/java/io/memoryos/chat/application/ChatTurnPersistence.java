package io.memoryos.chat.application;

import io.memoryos.ai.ChatModelResolver;
import io.memoryos.ai.ChatSampling;
import io.memoryos.ai.ModelAccounting;
import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatFileDescriptor;
import io.memoryos.library.ChatFileService;
import io.memoryos.library.LibraryException;
import io.memoryos.library.UserFile;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.ai.ChatModelBinding;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.image.GeneratedImage;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.identity.ActorLanguageService;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorized turn transactions; SQL remains in the concrete Chat repository.
 */
@Service
public class ChatTurnPersistence {
    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final JdbcChatRepository chats;
    private final ChatFileService files;
    private final ActorLanguageService languages;
    private final JdbcImageArtifactRepository imageArtifacts;
    private final io.memoryos.usage.@Nullable AiUsageRecorder usage;
    private final io.memoryos.chat.persistence.@Nullable JdbcChatPreferencesRepository preferences;
    private final io.memoryos.iam.identity.@Nullable ActorProfileReader profiles;

    public ChatTurnPersistence(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                               ChatFileService files, ActorLanguageService languages,
                               JdbcImageArtifactRepository imageArtifacts) {
        this(tenants, authorization, chats, files, languages, imageArtifacts, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatTurnPersistence(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                               ChatFileService files, ActorLanguageService languages,
                               JdbcImageArtifactRepository imageArtifacts, io.memoryos.usage.@Nullable AiUsageRecorder usage,
                               io.memoryos.chat.persistence.@Nullable JdbcChatPreferencesRepository preferences,
                               io.memoryos.iam.identity.@Nullable ActorProfileReader profiles) {
        this.usage = usage;
        this.preferences = preferences;
        this.profiles = profiles;
        this.tenants = tenants;
        this.authorization = authorization;
        this.chats = chats;
        this.files = files;
        this.languages = languages;
        this.imageArtifacts = imageArtifacts;
    }

    /** Onyx's user information section: login name and email, the member's role and preferences (MEM-145). */
    private String userInformation(UUID tenant, ActorId actor, String instructions) {
        if (preferences == null || profiles == null) return instructions;
        var own = preferences.find(tenant, actor.value()).orElse(io.memoryos.chat.preferences.ChatPreferences.DEFAULT);
        var profile = profiles.read(actor);
        return io.memoryos.chat.prompts.ChatPrompts.withUserInformation(instructions, profile.displayName(),
                profile.email(), own.workRole(), own.personalPreferences());
    }

    /**
     * The creativity and reasoning level for one turn, in Onyx's order: the level pinned on this conversation, then
     * the model configuration (which the adapter keeps when nothing outranks it), then the member's own defaults.
     */
    private ChatSampling sampling(TenantId tenant, ActorId actor, JdbcChatRepository.Persona settings) {
        var own = preferences == null ? io.memoryos.chat.preferences.ChatPreferences.DEFAULT
                : preferences.find(tenant.value(), actor.value())
                        .orElse(io.memoryos.chat.preferences.ChatPreferences.DEFAULT);
        var pinned = settings.reasoningEffort();
        var effort = pinned != null ? pinned : own.reasoningEffortDefault();
        if (own.temperatureDefault() == null && effort == null) return ChatSampling.NONE;
        return new ChatSampling(own.temperatureDefault(), effort, pinned != null);
    }

    /** The session agent's tool policy, read under the owner's agent use authority before a command is admitted. */
    @Transactional(readOnly = true)
    public JdbcChatRepository.Persona agent(ActorId actor, UUID session) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        return chats.persona(session, false, agentsManage(actor));
    }

    private boolean agentsManage(ActorId actor) {
        return authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_MANAGE);
    }

    /** Capability gate checked once per command or stream entry, before ownership and the session lock. */
    @Transactional(readOnly = true)
    public void require(ActorId actor, IamCapability capability) {
        authorization.require(actor, capability, false);
    }

    public record TitleInput(io.memoryos.chat.ChatSession session, List<ChatMessage> messages) {}

    @Transactional
    public Optional<TitleInput> claimTitle(ActorId actor, UUID sessionId) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        var session = chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        if (chats.hasActiveReply(sessionId)) return Optional.empty();
        var history = chats.history(session, null, 3);
        if (history.stream().noneMatch(message -> message.role() == ChatMessage.Role.ASSISTANT && message.status() == ChatMessage.Status.COMPLETED)) return Optional.empty();
        return chats.claimTitle(sessionId) ? Optional.of(new TitleInput(session, history)) : Optional.empty();
    }

    @Transactional
    public void completeTitle(ActorId actor, TitleInput input, String title) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        chats.findOwned(tenant, actor, input.session().id(), true).orElseThrow(ChatException::unavailable);
        chats.completeTitle(input.session(), title);
    }

    @Transactional
    public Reservation reserve(ActorId actor, UUID sessionId, UUID parentId, UUID requestId,
                               String text, Duration timeout, int contextTokenLimit) {
        return reserve(actor, sessionId, parentId, requestId, text, timeout, contextTokenLimit, null);
    }

    public record ModelSelection(@Nullable UUID requestedId, UUID selectedId, @Nullable String fallbackReason,
                                 ChatModelBinding binding, @Nullable String contextRevision, String promptContribution) {}

    @Transactional
    public Reservation reserve(ActorId actor, UUID sessionId, UUID parentId, UUID requestId,
                               String text, Duration lease, int contextTokenLimit, @Nullable ModelSelection selection) {
        return reserve(actor, sessionId, new ChatCommand(ChatCommand.Operation.SEND, parentId, requestId, text,
                selection == null ? null : selection.requestedId()), lease, contextTokenLimit, selection);
    }

    /** The lease is a liveness fence renewed by the running process, not a turn deadline. */
    @Transactional
    public Reservation reserve(ActorId actor, UUID sessionId, ChatCommand command,
                               Duration lease, int contextTokenLimit, @Nullable ModelSelection selection) {
        if (lease == null || lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofDays(1)) > 0) {
            throw ChatException.invalid("Invalid chat message or lease.");
        }
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        var session = chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        var previous = chats.previousRequest(sessionId, command.requestId());
        if (previous.isPresent()) {
            var user = previous.orElseThrow();
            match(user, command);
            return new Reservation(user.userMessageId(), user.assistantMessageId(), false, user.selectedModelId(), user.fallbackReason());
        }
        if (chats.hasActiveReply(sessionId)) throw ChatException.conflict();
        // A conversation someone is writing in is not archived (MEM-153), so this turn takes it back out.
        if (session.archived()) chats.unarchiveOnActivity(sessionId);
        var target = chats.message(sessionId, command.targetMessageId()).orElseThrow(ChatException::unavailable);
        if (!chats.onSelectedBranch(session, target.id())) throw ChatException.conflict();
        UUID parentId;
        String text;
        if (command.operation() == ChatCommand.Operation.SEND) {
            if (target.role() == ChatMessage.Role.USER || target.latestChildMessageId() != null) throw ChatException.conflict();
            parentId = target.id(); text = command.text();
        } else {
            if (target.role() != ChatMessage.Role.USER) throw ChatException.invalid("Select a user message.");
            parentId = Objects.requireNonNull(target.parentMessageId());
            text = command.operation() == ChatCommand.Operation.REGENERATE ? Objects.requireNonNull(target.content()) : command.text();
        }
        if (chats.messageCount(sessionId) > 9998) throw ChatException.invalid("Chat session message limit reached.");
        var settings = chats.persona(sessionId, true, agentsManage(actor));
        if (selection != null && selection.contextRevision() != null && !selection.contextRevision().equals(settings.revision()))
            throw ChatException.conflict();
        int effectiveContext = settings.options().contextTokenLimit() == null ? contextTokenLimit
                : Math.min(contextTokenLimit, settings.options().contextTokenLimit());
        String instructions = settings.instructions();
        if (selection == null) ChatTurnSetup.validateQuestion(instructions, text, effectiveContext);
        else {
            var binding = selection.binding().forOptions(settings.options().sampling(), settings.options().outputTokenLimit());
            instructions = io.memoryos.chat.prompts.ChatPrompts.resolve(instructions,
                    binding.toolCalling() && settings.options().searchEnabled(), Instant.now(), languages.read(actor), settings.datetimeAware());
            instructions = userInformation(tenant.value(), actor, instructions);
            ChatTurnSetup.validateQuestion(instructions, text, effectiveContext, binding, selection.promptContribution());
        }
        UUID user = command.operation() == ChatCommand.Operation.REGENERATE ? target.id() : UUID.randomUUID();
        var attachments = command.operation() == ChatCommand.Operation.REGENERATE ? target.files()
                : descriptors(files.admit(tenant, actor, command.fileIds()));
        UUID assistant = UUID.randomUUID();
        if (command.operation() == ChatCommand.Operation.REGENERATE) chats.insertAssistant(sessionId, user, assistant, lease);
        else chats.insertPair(sessionId, parentId, command.requestId(), user, assistant, text, lease, attachments);
        // An upload sent into a temporary conversation belongs to it (MEM-153): the library stops listing the
        // file, and the purge releases its bytes with the conversation.
        if (session.temporary()) {
            files.claimTemporary(tenant, actor, sessionId, attachments.stream().map(ChatFileDescriptor::id).toList());
        }
        if (selection != null) chats.saveModelSelection(sessionId,
                command.operation() == ChatCommand.Operation.REGENERATE ? assistant : user,
                assistant, selection.requestedId(), selection.selectedId(), selection.fallbackReason());
        chats.saveCommand(sessionId, command, user, assistant, selection == null ? null : selection.selectedId(),
                selection == null ? null : selection.fallbackReason());
        var context = context(actor, tenant, sessionId, user, settings, instructions);
        return new Reservation(user, assistant, true, selection == null ? null : selection.selectedId(), selection == null ? null : selection.fallbackReason(), context);
    }

    @Transactional
    public boolean finish(UUID sessionId, UUID assistantId, ChatMessage.Status status, String partialContent) {
        if (status == null || status == ChatMessage.Status.RUNNING || partialContent == null || partialContent.length() > 1000000) {
            throw ChatException.invalid("Invalid terminal outcome.");
        }
        return finish(sessionId, assistantId, status, partialContent, null, null, null, null, null);
    }

    public record Reservation(UUID userMessageId, UUID assistantMessageId, boolean created,
                              @Nullable UUID modelConfigurationId, @Nullable String fallbackReason, @Nullable TurnContext context) {
        public Reservation(UUID userMessageId, UUID assistantMessageId, boolean created, @Nullable UUID modelConfigurationId, @Nullable String fallbackReason) {
            this(userMessageId, assistantMessageId, created, modelConfigurationId, fallbackReason, null);
        }
        public Reservation(UUID userMessageId, UUID assistantMessageId, boolean created) {
            this(userMessageId, assistantMessageId, created, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Reservation> existing(ActorId actor, UUID session, UUID parent, UUID request, String text) {
        return existing(actor, session, parent, request, text, null);
    }

    @Transactional(readOnly = true)
    public Optional<Reservation> existing(ActorId actor, UUID session, UUID parent, UUID request, String text, @Nullable UUID requestedModelId) {
        return existing(actor, session, new ChatCommand(ChatCommand.Operation.SEND, parent, request, text, requestedModelId));
    }

    @Transactional(readOnly = true)
    public Optional<Reservation> existing(ActorId actor, UUID session, ChatCommand command) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        return chats.previousRequest(session, command.requestId()).map(previous -> {
            match(previous, command);
            return new Reservation(previous.userMessageId(), previous.assistantMessageId(), false, previous.selectedModelId(), previous.fallbackReason());
        });
    }

    /** Deep research is rejected for Project chats, as Onyx; the session's Project is read before reserving. */
    @Transactional(readOnly = true)
    public boolean inProject(ActorId actor, UUID session) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable).projectId() != null;
    }

    private static void match(JdbcChatRepository.ReservedRequest previous, ChatCommand command) {
        if (previous.operation() != command.operation() || !previous.parentMessageId().equals(command.targetMessageId())
                || !previous.content().equals(command.text()) || !previous.fileIds().equals(command.fileIds())
                || previous.webSearch() != command.webSearch() || previous.deepResearch() != command.deepResearch()
                || !Objects.equals(previous.requestedModelId(), command.modelConfigurationId()))
            throw ChatException.conflict();
    }

    @Transactional
    public TurnContext loadContext(ActorId actor, UUID sessionId, Reservation reservation) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
        if (reservation.context() != null) return reservation.context();
        var persona = chats.persona(sessionId, false, agentsManage(actor));
        return context(actor, tenant, sessionId, reservation.userMessageId(), persona, persona.instructions());
    }

    private TurnContext context(ActorId actor, TenantId tenant, UUID session, UUID user, JdbcChatRepository.Persona settings, String instructions) {
        var history = chats.context(session, user, 200);
        var workspaceFiles = descriptors(files.admit(tenant, actor, settings.fileIds()));
        var plaintext = new LinkedHashMap<UUID, ChatFileService.FileText>();
        java.util.stream.Stream.concat(history.stream().flatMap(message -> message.files().stream()), workspaceFiles.stream())
                .map(ChatFileDescriptor::id)
                .distinct().limit(20).forEach(id -> {
                    try { plaintext.put(id, files.read(actor, tenant, id, 0, 16000)); }
                    catch (LibraryException unavailable) { /* Old descriptors survive deletion, not authority. */ }
                });
        // History keeps assistant replies as text; name their images so a later turn can edit one.
        var generated = new LinkedHashMap<UUID, List<UUID>>();
        imageArtifacts.byMessages(tenant, history.stream().filter(message -> message.role() == ChatMessage.Role.ASSISTANT)
                .map(ChatMessage::id).toList(), false).forEach((message, images) ->
                generated.put(message, images.stream().map(GeneratedImage::id).toList()));
        return new TurnContext(actor, tenant, settings.model(), instructions, history,
                settings.options().withSampling(sampling(tenant, actor, settings)), plaintext, workspaceFiles,
                languages.read(actor), generated);
    }

    /** A message's attachments as Chat records them, from the library files it admitted. */
    private static List<ChatFileDescriptor> descriptors(List<UserFile> files) {
        return files.stream().map(ChatFileDescriptor::from).toList();
    }

    public record TurnContext(ActorId actor, TenantId tenant, String model, String instructions,
                              List<ChatMessage> newestFirst, ChatTurnOptions options,
                              Map<UUID, ChatFileService.FileText> fileTexts, List<ChatFileDescriptor> workspaceFiles,
                              @Nullable String uiLanguage, Map<UUID, List<UUID>> generatedImages) {
        public TurnContext { newestFirst = List.copyOf(newestFirst); fileTexts = Map.copyOf(fileTexts); workspaceFiles = List.copyOf(workspaceFiles); generatedImages = Map.copyOf(generatedImages); }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst, ChatTurnOptions options,
                           Map<UUID, ChatFileService.FileText> fileTexts, List<ChatFileDescriptor> workspaceFiles, @Nullable String uiLanguage) {
            this(actor, tenant, model, instructions, newestFirst, options, fileTexts, workspaceFiles, uiLanguage, Map.of());
        }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst, ChatTurnOptions options,
                           Map<UUID, ChatFileService.FileText> fileTexts, List<ChatFileDescriptor> workspaceFiles) {
            this(actor, tenant, model, instructions, newestFirst, options, fileTexts, workspaceFiles, null);
        }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst, ChatTurnOptions options) {
            this(actor, tenant, model, instructions, newestFirst, options, Map.of(), List.of());
        }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst) {
            this(actor, tenant, model, instructions, newestFirst, ChatTurnOptions.DEFAULT);
        }
    }

    /** Renews rows still RUNNING and returns their IDs; a missing ID means the run no longer owns its row. */
    @Transactional
    public java.util.Set<UUID> renewLeases(java.util.Collection<UUID> assistants, Duration lease) {
        return chats.renewLeases(assistants, lease);
    }

    @Transactional
    public ChatMessage.Status authorizeReply(ActorId actor, UUID session, UUID assistant) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.findOwned(tenant, actor, session, true).orElseThrow(ChatException::unavailable);
        var message = chats.message(session, assistant).orElseThrow(ChatException::unavailable);
        if (message.role() != ChatMessage.Role.ASSISTANT)
            throw ChatException.invalid("Only an assistant reply can be stopped.");
        return message.status();
    }

    @Transactional(readOnly = true)
    public JdbcChatRepository.Control control(UUID assistant) {
        return chats.control(assistant);
    }

    @Transactional
    public boolean finish(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output, @Nullable Double cost) {
        if (status == null || status == ChatMessage.Status.RUNNING || partial == null || partial.length() > 1000000)
            throw ChatException.invalid("Invalid terminal outcome.");
        return chats.finish(session, assistant, status, partial, failure, model, input, output, cost);
    }

    public record TerminalOutcome(ChatMessage.Status status, @Nullable String failureCode, boolean hasArtifacts) {
        public TerminalOutcome(ChatMessage.Status status, @Nullable String failureCode) { this(status, failureCode, false); }
    }

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources) {
        return finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources, List.of());
    }

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, List<io.memoryos.chat.ChatArtifact> artifacts) {
        return finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources, artifacts, io.memoryos.chat.ChatActivity.EMPTY);
    }

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, List<io.memoryos.chat.ChatArtifact> artifacts,
                          io.memoryos.chat.ChatActivity activity) {
        return finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources, artifacts, activity,
                io.memoryos.chat.ChatResearch.EMPTY);
    }

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, List<io.memoryos.chat.ChatArtifact> artifacts,
                          io.memoryos.chat.ChatActivity activity, io.memoryos.chat.ChatResearch research) {
        if (status == null || status == ChatMessage.Status.RUNNING || partial == null || partial.length() > 1000000)
            throw ChatException.invalid("Invalid terminal outcome.");
        if (artifacts.size() > 3) throw ChatException.invalid("Invalid artifact count.");
        chats.finish(session, assistant, status, partial, failure, model, input, output, cost, sources, artifacts, activity, research);
        var saved = chats.control(assistant);
        return new TerminalOutcome(saved.status(), saved.failureCode(), chats.message(session, assistant).map(message -> !message.artifacts().isEmpty()).orElse(false));
    }

    /**
     * One turn's or naming call's AI usage. Nothing is recorded when no model call ran; unknown totals are counted as
     * calls with unknown cost, never as zero.
     */
    public record Usage(@Nullable TenantId tenant, ActorId actor, io.memoryos.usage.AiUsageFlow flow, @Nullable UUID modelConfigurationId,
                        ChatModelResolver.Provenance provider, String modelName,
                        ModelAccounting accounting) {
        io.memoryos.usage.@Nullable AiUsage call(TenantId tenant, Instant at) {
            if (!accounting.used()) return null;
            if (accounting.input() == null || accounting.output() == null)
                return io.memoryos.usage.AiUsage.unknown(tenant.value(), actor.value(), flow, provider.providerName(), modelName,
                        provider.providerId(), modelConfigurationId, provider.dataBoundary(), at);
            return io.memoryos.usage.AiUsage.tokens(tenant.value(), actor.value(), flow, provider.providerName(), modelName,
                    provider.providerId(), modelConfigurationId, provider.dataBoundary(), accounting.input(), accounting.output(),
                    Math.min(accounting.cacheRead(), accounting.input()), accounting.cost(), at);
        }
    }

    /** Finishes the turn and adds its usage in the same transaction, so spend is never lost or double counted. */
    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, List<io.memoryos.chat.ChatArtifact> artifacts,
                          io.memoryos.chat.ChatActivity activity, io.memoryos.chat.ChatResearch research, Usage turnUsage) {
        var outcome = finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources, artifacts, activity, research);
        record(turnUsage);
        return outcome;
    }

    /** Usage of work that has no turn of its own, such as conversation naming. */
    @Transactional
    public void recordUsage(Usage call) { record(call); }

    private void record(Usage call) {
        if (usage == null || !call.accounting().used()) return;
        // Naming has no turn and knows only its actor; its Tenant is the actor's active one.
        var tenant = call.tenant() != null ? call.tenant() : tenants.findActiveTenant(call.actor()).orElse(null);
        if (tenant == null) return;
        var usageCall = call.call(tenant, Instant.now());
        if (usageCall != null) usage.record(usageCall);
    }

    @Transactional
    public int failOrphanedRuns() {
        return chats.failOrphanedRuns();
    }

    @Transactional
    public int expireRuns() {
        return chats.expireRuns();
    }

    @Transactional(readOnly = true)
    public List<UUID> ownedSessions(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return chats.ownedIds(tenant, actor);
    }

    @Transactional
    public List<UUID> delete(ActorId actor, UUID session) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        chats.findOwned(tenant, actor, session, true).orElseThrow(ChatException::unavailable);
        chats.delete(session);
        return chats.branches(session).stream().map(io.memoryos.chat.ChatBranch::id).toList();
    }
}
