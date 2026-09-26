package io.memoryos.chat.session;

import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.ModelSampling;
import io.memoryos.ai.ModelAccounting;
import io.memoryos.chat.ChatActivity;
import io.memoryos.chat.ChatBranch;
import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatFileDescriptor;
import io.memoryos.chat.ChatPreferences;
import io.memoryos.chat.ChatResearch;
import io.memoryos.chat.ChatSession;
import io.memoryos.chat.preferences.persistence.JdbcChatPreferencesRepository;
import io.memoryos.chat.prompts.ChatPrompts;
import io.memoryos.iam.ActorProfileReader;
import io.memoryos.library.UserFileService;
import io.memoryos.library.UserFile;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.ai.ModelBinding;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.image.GeneratedImage;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.image.persistence.JdbcImageArtifactRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.ActorLanguageService;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final UserFileService files;
    private final ActorLanguageService languages;
    private final JdbcImageArtifactRepository imageArtifacts;
    private final @Nullable AiUsageRecorder usage;
    private final @Nullable JdbcChatPreferencesRepository preferences;
    private final @Nullable ActorProfileReader profiles;

    public ChatTurnPersistence(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                               UserFileService files, ActorLanguageService languages,
                               JdbcImageArtifactRepository imageArtifacts) {
        this(tenants, authorization, chats, files, languages, imageArtifacts, null, null, null);
    }

    @Autowired
    public ChatTurnPersistence(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                               UserFileService files, ActorLanguageService languages,
                               JdbcImageArtifactRepository imageArtifacts, @Nullable AiUsageRecorder usage,
                               @Nullable JdbcChatPreferencesRepository preferences,
                               @Nullable ActorProfileReader profiles) {
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

    /** The member's own Chat preferences, read once per turn. */
    private ChatPreferences preferences(TenantId tenant, ActorId actor) {
        return preferences == null ? ChatPreferences.DEFAULT
                : preferences.find(tenant.value(), actor.value()).orElse(ChatPreferences.DEFAULT);
    }

    /** Onyx's user information section: login name and email, the member's role and preferences (MEM-145). */
    private String userInformation(ActorId actor, String instructions, ChatPreferences own) {
        if (preferences == null || profiles == null) return instructions;
        var profile = profiles.read(actor);
        return ChatPrompts.withUserInformation(instructions, profile.displayName(),
                profile.email(), own.workRole(), own.personalPreferences());
    }

    /**
     * The creativity and reasoning level for one turn, in Onyx's order: the level pinned on this conversation, then
     * the model configuration (which the adapter keeps when nothing outranks it), then the member's own defaults.
     */
    private static ModelSampling sampling(JdbcChatRepository.Persona settings, ChatPreferences own) {
        var pinned = settings.reasoningEffort();
        var effort = pinned != null ? pinned : own.reasoningEffortDefault();
        if (own.temperatureDefault() == null && effort == null) return ModelSampling.NONE;
        return new ModelSampling(own.temperatureDefault(), effort, pinned != null);
    }

    /**
     * The session agent as one turn uses it, with the capabilities that decide which agents and models the owner may
     * use. Read once, before a command is admitted, and carried through model selection into the reservation, which
     * rechecks its revision under a share lock instead of reading it again.
     */
    public record SessionAgent(JdbcChatRepository.Persona persona, boolean agentsManage, boolean modelsManage,
                               boolean inProject) {}

    /** The session agent's tool policy, read under the owner's agent use authority before a command is admitted. */
    @Transactional(readOnly = true)
    public SessionAgent agent(ActorId actor, UUID session) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var owned = chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        var capabilities = authorization.effectiveCapabilities(actor);
        boolean agentsManage = capabilities.contains(IamCapability.AGENTS_MANAGE);
        return new SessionAgent(chats.persona(session, false, agentsManage), agentsManage,
                capabilities.contains(IamCapability.MODELS_MANAGE), owned.projectId() != null);
    }

    private boolean agentsManage(ActorId actor) {
        return authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_MANAGE);
    }

    /** Capability gate checked once per command or stream entry, before ownership and the session lock. */
    @Transactional(readOnly = true)
    public void require(ActorId actor, IamCapability capability) {
        authorization.require(actor, capability, false);
    }

    public record TitleInput(ChatSession session, List<ChatMessage> messages) {}

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

    /** {@code agent} is the session agent the selection was made with; null reads the agent in the reservation. */
    public record ModelSelection(@Nullable UUID requestedId, UUID selectedId, @Nullable String fallbackReason,
                                 ModelBinding binding, @Nullable String contextRevision, String promptContribution,
                                 @Nullable SessionAgent agent) {
        public ModelSelection(@Nullable UUID requestedId, UUID selectedId, @Nullable String fallbackReason,
                              ModelBinding binding, @Nullable String contextRevision, String promptContribution) {
            this(requestedId, selectedId, fallbackReason, binding, contextRevision, promptContribution, null);
        }
    }

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
        var carried = selection == null ? null : selection.agent();
        JdbcChatRepository.Persona settings;
        if (carried != null) {
            // The agent was read before selection; its revision, share-locked here, proves it is still the one used.
            settings = carried.persona();
            if (!settings.revision().equals(chats.personaRevision(sessionId, carried.agentsManage()))
                    || selection.contextRevision() != null && !selection.contextRevision().equals(settings.revision()))
                throw ChatException.conflict();
        } else {
            settings = chats.persona(sessionId, true, agentsManage(actor));
            if (selection != null && selection.contextRevision() != null && !selection.contextRevision().equals(settings.revision()))
                throw ChatException.conflict();
        }
        var own = preferences(tenant, actor);
        String language = languages.read(actor);
        int effectiveContext = settings.options().contextTokenLimit() == null ? contextTokenLimit
                : Math.min(contextTokenLimit, settings.options().contextTokenLimit());
        String instructions = settings.instructions();
        if (selection == null) ChatTurnSetup.validateQuestion(instructions, text, effectiveContext);
        else {
            var binding = selection.binding().forOptions(settings.options().sampling(), settings.options().outputTokenLimit());
            instructions = ChatPrompts.resolve(instructions,
                    binding.toolCalling() && settings.options().searches(), Instant.now(), language);
            instructions = userInformation(actor, instructions, own);
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
        var context = context(actor, tenant, sessionId, user, settings, instructions, own, language);
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

    private static void match(JdbcChatRepository.ReservedRequest previous, ChatCommand command) {
        if (previous.operation() != command.operation() || !previous.parentMessageId().equals(command.targetMessageId())
                || !previous.content().equals(command.text()) || !previous.fileIds().equals(command.fileIds())
                || previous.webSearch() != command.webSearch() || previous.deepResearch() != command.deepResearch()
                || !Objects.equals(previous.requestedModelId(), command.modelConfigurationId()))
            throw ChatException.conflict();
    }

    /** The context a new reservation already carries, or the context of a replayed one, read again. */
    @Transactional
    public TurnContext loadContext(ActorId actor, UUID sessionId, Reservation reservation) {
        if (reservation.context() != null) return reservation.context();
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
        var persona = chats.persona(sessionId, false, agentsManage(actor));
        return context(actor, tenant, sessionId, reservation.userMessageId(), persona, persona.instructions(),
                preferences(tenant, actor), languages.read(actor));
    }

    private TurnContext context(ActorId actor, TenantId tenant, UUID session, UUID user, JdbcChatRepository.Persona settings,
                                String instructions, ChatPreferences own, @Nullable String language) {
        var history = chats.context(session, user, 200);
        var workspaceFiles = descriptors(files.admit(tenant, actor, settings.fileIds()));
        // Old descriptors survive deletion, not authority: a file the owner can no longer read is left out.
        var plaintext = files.readAll(tenant, actor, Stream.concat(
                        history.stream().flatMap(message -> message.files().stream()), workspaceFiles.stream())
                .map(ChatFileDescriptor::id).distinct().limit(20).toList(), 16000);
        // History keeps assistant replies as text; name their images so a later turn can edit one.
        var generated = new LinkedHashMap<UUID, List<UUID>>();
        imageArtifacts.byMessages(tenant, history.stream().filter(message -> message.role() == ChatMessage.Role.ASSISTANT)
                .map(ChatMessage::id).toList(), false).forEach((message, images) ->
                generated.put(message, images.stream().map(GeneratedImage::id).toList()));
        return new TurnContext(actor, tenant, settings.model(), instructions, history,
                settings.options().withSampling(sampling(settings, own)), plaintext, workspaceFiles,
                language, generated);
    }

    /** A message's attachments as Chat records them, from the library files it admitted. */
    private static List<ChatFileDescriptor> descriptors(List<UserFile> files) {
        return files.stream().map(ChatFileDescriptor::from).toList();
    }

    public record TurnContext(ActorId actor, TenantId tenant, String model, String instructions,
                              List<ChatMessage> newestFirst, ChatTurnOptions options,
                              Map<UUID, UserFileService.FileText> fileTexts, List<ChatFileDescriptor> workspaceFiles,
                              @Nullable String uiLanguage, Map<UUID, List<UUID>> generatedImages) {
        public TurnContext { newestFirst = List.copyOf(newestFirst); fileTexts = Map.copyOf(fileTexts); workspaceFiles = List.copyOf(workspaceFiles); generatedImages = Map.copyOf(generatedImages); }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst, ChatTurnOptions options,
                           Map<UUID, UserFileService.FileText> fileTexts, List<ChatFileDescriptor> workspaceFiles, @Nullable String uiLanguage) {
            this(actor, tenant, model, instructions, newestFirst, options, fileTexts, workspaceFiles, uiLanguage, Map.of());
        }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst, ChatTurnOptions options,
                           Map<UUID, UserFileService.FileText> fileTexts, List<ChatFileDescriptor> workspaceFiles) {
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
    public Set<UUID> renewLeases(Collection<UUID> assistants, Duration lease) {
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

    /** The terminal winner of a reply: the outcome just written, or the one that won before it. */
    public record TerminalOutcome(ChatMessage.Status status, @Nullable String failureCode) {}

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources) {
        return finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources,
                ChatActivity.EMPTY);
    }

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, ChatActivity activity) {
        return finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources, activity,
                ChatResearch.EMPTY);
    }

    /** Writes the terminal outcome and reads the winner from the same statement; a late write reads the earlier one. */
    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, ChatActivity activity,
                          ChatResearch research) {
        return finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources, activity, research,
                (String) null);
    }

    /** {@code refusal} marks a completed answer that declined (MEM-195), or is null. */
    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, ChatActivity activity,
                          ChatResearch research, @Nullable String refusal) {
        if (status == null || status == ChatMessage.Status.RUNNING || partial == null || partial.length() > 1000000)
            throw ChatException.invalid("Invalid terminal outcome.");
        var saved = chats.finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources,
                activity, research, refusal);
        return new TerminalOutcome(saved.status(), saved.failureCode());
    }

    /**
     * One turn's or naming call's AI usage. Nothing is recorded when no model call ran; unknown totals are counted as
     * calls with unknown cost, never as zero.
     */
    public record Usage(@Nullable TenantId tenant, ActorId actor, AiUsageFlow flow, @Nullable UUID modelConfigurationId,
                        ModelResolver.Provenance provider, String modelName,
                        ModelAccounting accounting) {
        @Nullable AiUsage call(TenantId tenant, Instant at) {
            if (!accounting.used()) return null;
            if (accounting.input() == null || accounting.output() == null)
                return AiUsage.unknown(tenant.value(), actor.value(), flow, provider.providerName(), modelName,
                        provider.providerId(), modelConfigurationId, provider.dataBoundary(), at);
            return AiUsage.tokens(tenant.value(), actor.value(), flow, provider.providerName(), modelName,
                    provider.providerId(), modelConfigurationId, provider.dataBoundary(), accounting.input(), accounting.output(),
                    Math.min(accounting.cacheRead(), accounting.input()), accounting.cost(), at);
        }
    }

    /** Finishes the turn and adds its usage in the same transaction, so spend is never lost or double counted. */
    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, ChatActivity activity,
                          ChatResearch research, @Nullable String refusal, Usage turnUsage) {
        var outcome = finishAndRead(session, assistant, status, partial, failure, model, input, output, cost, sources, activity, research, refusal);
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
        return chats.branches(session).stream().map(ChatBranch::id).toList();
    }
}
