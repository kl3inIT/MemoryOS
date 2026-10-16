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
import io.memoryos.chat.preferences.persistence.JdbcChatMemoryRepository;
import io.memoryos.chat.preferences.persistence.JdbcChatPreferencesRepository;
import io.memoryos.chat.prompts.ChatPrompts;
import io.memoryos.iam.ActorProfileReader;
import io.memoryos.library.UserFileService;
import io.memoryos.library.UserFile;
import io.memoryos.chat.ChatContextUse;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatToolApp;
import io.memoryos.chat.ChatToolTranscript;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.ai.ModelBinding;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.image.GeneratedImage;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.session.persistence.JdbcChatSummaryRepository;
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
    private final JdbcChatSummaryRepository summaries;
    private final @Nullable AiUsageRecorder usage;
    private final @Nullable JdbcChatPreferencesRepository preferences;
    private final @Nullable ActorProfileReader profiles;
    private final @Nullable JdbcChatMemoryRepository memories;

    public ChatTurnPersistence(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                               UserFileService files, ActorLanguageService languages,
                               JdbcImageArtifactRepository imageArtifacts, JdbcChatSummaryRepository summaries) {
        this(tenants, authorization, chats, files, languages, imageArtifacts, summaries, null, null, null, null);
    }

    @Autowired
    public ChatTurnPersistence(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                               UserFileService files, ActorLanguageService languages,
                               JdbcImageArtifactRepository imageArtifacts, JdbcChatSummaryRepository summaries,
                               @Nullable AiUsageRecorder usage,
                               @Nullable JdbcChatPreferencesRepository preferences,
                               @Nullable ActorProfileReader profiles, @Nullable JdbcChatMemoryRepository memories) {
        this.usage = usage;
        this.preferences = preferences;
        this.memories = memories;
        this.profiles = profiles;
        this.tenants = tenants;
        this.authorization = authorization;
        this.chats = chats;
        this.files = files;
        this.languages = languages;
        this.imageArtifacts = imageArtifacts;
        this.summaries = summaries;
    }

    /** The member's own Chat preferences, read once per turn. */
    private ChatPreferences preferences(TenantId tenant, ActorId actor) {
        return preferences == null ? ChatPreferences.DEFAULT
                : preferences.find(tenant.value(), actor.value()).orElse(ChatPreferences.DEFAULT);
    }

    /**
     * Onyx's user information section: login name and email, the member's role and preferences (MEM-145), and what
     * Chat remembers about them (MEM-250) when the memory may be used in this conversation (on, not temporary, web).
     */
    private String userInformation(TenantId tenant, ActorId actor, String instructions, ChatPreferences own,
                                   UUID session) {
        if (preferences == null || profiles == null) return instructions;
        var profile = profiles.read(actor);
        var memory = memories != null && memories.usableIn(tenant.value(), actor.value(), session)
                ? memories.find(tenant.value(), actor.value()).content() : "";
        return ChatPrompts.withUserInformation(instructions, profile.displayName(),
                profile.email(), own.workRole(), own.personalPreferences(), memory);
    }

    /**
     * The creativity and reasoning level for one turn: the level pinned on this conversation, then the agent's own
     * (MEM-273), either outranking the model configuration; then, as Onyx orders it, the model configuration (which the
     * adapter keeps when nothing outranks it) and the member's own defaults.
     */
    static ModelSampling sampling(JdbcChatRepository.Persona settings, ChatPreferences own) {
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

    /**
     * The session agent's tool policy, read under the owner's agent use authority before a command is admitted;
     * {@code fromChannel} when the channel asks. A conversation held in a channel may
     * use its bot's assistant only for questions asked through the bot (MEM-273): a command from anywhere else, such as
     * the web, needs the owner to be able to use the assistant themself.
     */
    @Transactional(readOnly = true)
    public SessionAgent agent(ActorId actor, UUID session, boolean fromChannel) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var owned = chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        var capabilities = authorization.effectiveCapabilities(actor);
        boolean agentsManage = capabilities.contains(IamCapability.AGENTS_MANAGE);
        if (!fromChannel && chats.heldInChannel(session)
                && !chats.usablePersona(tenant, actor, owned.personaId(), agentsManage)) {
            throw ChatException.unavailable();
        }
        return new SessionAgent(chats.persona(session, false, agentsManage), agentsManage,
                capabilities.contains(IamCapability.MODELS_MANAGE), owned.projectId() != null);
    }

    /**
     * The assistant of a temporary conversation the caller owns, whose unsaved settings a test turn may run (MEM-273);
     * a conversation that is kept is refused.
     */
    @Transactional(readOnly = true)
    public UUID testedAssistant(ActorId actor, UUID session) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var owned = chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        if (!owned.temporary()) throw ChatException.invalid("Only a temporary conversation runs unsaved assistant settings.");
        return owned.personaId();
    }

    /** The session agent built from its assistant's checked, unsaved settings instead of the saved ones. */
    @Transactional(readOnly = true)
    public SessionAgent agent(ActorId actor, UUID session, JdbcChatRepository.DraftAgent draft) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var owned = chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        var capabilities = authorization.effectiveCapabilities(actor);
        boolean agentsManage = capabilities.contains(IamCapability.AGENTS_MANAGE);
        return new SessionAgent(chats.draftPersona(session, agentsManage, draft), agentsManage,
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
        return reserve(actor, sessionId, ChatCommand.builder(ChatCommand.Operation.SEND, parentId, requestId, text)
                .modelConfigurationId(selection == null ? null : selection.requestedId())
                .build(), lease, contextTokenLimit, selection);
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
        String instructions = settings.instructions();
        if (selection == null) ChatTurnSetup.validateQuestion(instructions, text, contextTokenLimit);
        else {
            var binding = selection.binding().forOptions(settings.options().sampling());
            instructions = ChatPrompts.resolve(instructions,
                    binding.toolCalling() && settings.options().searches(), Instant.now(), language);
            instructions = ChatPrompts.withChannel(instructions, command.channel());
            instructions = userInformation(tenant, actor, instructions, own, sessionId);
            ChatTurnSetup.validateQuestion(instructions, text, contextTokenLimit, binding, selection.promptContribution());
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
        return existing(actor, session, ChatCommand.builder(ChatCommand.Operation.SEND, parent, request, text)
                .modelConfigurationId(requestedModelId)
                .build());
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
        var summary = summaries.forTurn(tenant, session, user)
                .map(found -> new TurnContext.Summary(found.cutoffMessageId(), found.content())).orElse(null);
        // MEM-287: earlier answers' tool calls, replayed before each answer on this branch.
        var toolCalls = chats.toolCalls(history.stream().filter(message -> message.role() == ChatMessage.Role.ASSISTANT)
                .map(ChatMessage::id).toList());
        return new TurnContext(actor, tenant, instructions, history,
                settings.options().withSampling(sampling(settings, own)), plaintext, workspaceFiles,
                language, generated, summary, toolCalls);
    }

    /** A message's attachments as Chat records them, from the library files it admitted. */
    private static List<ChatFileDescriptor> descriptors(List<UserFile> files) {
        return files.stream().map(ChatFileDescriptor::from).toList();
    }

    public record TurnContext(ActorId actor, TenantId tenant, String instructions,
                              List<ChatMessage> newestFirst, ChatTurnOptions options,
                              Map<UUID, UserFileService.FileText> fileTexts, List<ChatFileDescriptor> workspaceFiles,
                              @Nullable String uiLanguage, Map<UUID, List<UUID>> generatedImages,
                              @Nullable Summary summary, Map<UUID, List<ChatToolTranscript.Call>> toolCalls) {
        public TurnContext { newestFirst = List.copyOf(newestFirst); fileTexts = Map.copyOf(fileTexts); workspaceFiles = List.copyOf(workspaceFiles); generatedImages = Map.copyOf(generatedImages); toolCalls = Map.copyOf(toolCalls); }

        /** MEM-268: the branch's summary of the turns up to and including {@code cutoffMessageId}. */
        public record Summary(UUID cutoffMessageId, String content) {
        }

        /** The same context replaying its whole window, as a Deep research turn does. */
        public TurnContext withoutSummary() {
            return summary == null ? this : new TurnContext(actor, tenant, instructions, newestFirst, options, fileTexts,
                    workspaceFiles, uiLanguage, generatedImages, null, toolCalls);
        }

        /** The same context without earlier tool calls, as a Deep research turn replays (MEM-287). */
        public TurnContext withoutToolCalls() {
            return toolCalls.isEmpty() ? this : new TurnContext(actor, tenant, instructions, newestFirst, options, fileTexts,
                    workspaceFiles, uiLanguage, generatedImages, summary, Map.of());
        }

        /** A context with default options and no files, account language, generated images or summary. */
        public static Builder builder(ActorId actor, TenantId tenant, String instructions,
                                      List<ChatMessage> newestFirst) {
            return new Builder(actor, tenant, instructions, newestFirst);
        }

        public static final class Builder {
            private final ActorId actor;
            private final TenantId tenant;
            private final String instructions;
            private final List<ChatMessage> newestFirst;
            private ChatTurnOptions options = ChatTurnOptions.DEFAULT;
            private Map<UUID, UserFileService.FileText> fileTexts = Map.of();
            private List<ChatFileDescriptor> workspaceFiles = List.of();
            private @Nullable String uiLanguage;
            private Map<UUID, List<UUID>> generatedImages = Map.of();
            private @Nullable Summary summary;
            private Map<UUID, List<ChatToolTranscript.Call>> toolCalls = Map.of();

            private Builder(ActorId actor, TenantId tenant, String instructions,
                            List<ChatMessage> newestFirst) {
                this.actor = actor;
                this.tenant = tenant;
                this.instructions = instructions;
                this.newestFirst = newestFirst;
            }

            public Builder options(ChatTurnOptions value) { options = value; return this; }
            public Builder fileTexts(Map<UUID, UserFileService.FileText> value) { fileTexts = value; return this; }
            public Builder workspaceFiles(List<ChatFileDescriptor> value) { workspaceFiles = value; return this; }
            public Builder uiLanguage(@Nullable String value) { uiLanguage = value; return this; }
            public Builder generatedImages(Map<UUID, List<UUID>> value) { generatedImages = value; return this; }
            public Builder summary(@Nullable Summary value) { summary = value; return this; }
            public Builder toolCalls(Map<UUID, List<ChatToolTranscript.Call>> value) { toolCalls = value; return this; }

            public TurnContext build() {
                return new TurnContext(actor, tenant, instructions, newestFirst, options, fileTexts,
                        workspaceFiles, uiLanguage, generatedImages, summary, toolCalls);
            }
        }
    }

    /**
     * MEM-287: keeps a finished answer's tool calls for later turns of its branch, and (MEM-275) the views of its MCP
     * calls for its owner's screen.
     */
    @Transactional
    public void recordToolCalls(UUID assistant, List<ChatToolTranscript.Call> calls, List<ChatToolApp> apps) {
        if (!calls.isEmpty()) chats.saveToolCalls(assistant, calls);
        if (!apps.isEmpty()) chats.saveToolApps(assistant, apps);
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
                    Math.min(accounting.cacheRead(), accounting.input()), accounting.cost(), accounting.complete(), at);
        }
    }

    /** What the reply's last model call carried, for the composer's context gauge (MEM-267). */
    @Transactional
    public void recordContext(UUID session, UUID assistant, ChatContextUse context) {
        chats.recordContext(session, assistant, context);
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

    /**
     * MEM-268: what a compaction after the answer {@code assistant} reads: the branch from that answer back, newest
     * first, and the summary the next turn on it would replay. Empty when the conversation is no longer the actor's.
     */
    public record CompactionInput(TenantId tenant, List<ChatMessage> newestFirst, TurnContext.@Nullable Summary summary) {
        public CompactionInput {
            newestFirst = List.copyOf(newestFirst);
        }
    }

    @Transactional(readOnly = true)
    public Optional<CompactionInput> compactionInput(ActorId actor, UUID session, UUID assistant) {
        var tenant = tenants.findActiveTenant(actor).orElse(null);
        if (tenant == null || chats.findOwned(tenant, actor, session, false).isEmpty()) return Optional.empty();
        var summary = summaries.forTurn(tenant, session, assistant)
                .map(found -> new TurnContext.Summary(found.cutoffMessageId(), found.content())).orElse(null);
        return Optional.of(new CompactionInput(tenant, chats.context(session, assistant, 200), summary));
    }

    /** Claims the session's compaction for {@code hold}; false while another holds it or a failure asked to wait. */
    @Transactional
    public boolean claimCompaction(TenantId tenant, UUID session, Duration hold) {
        return summaries.claim(tenant, session, hold);
    }

    /** Ends the session's claim; after a failure, the session is not compacted again before {@code retryAfter}. */
    @Transactional
    public void releaseCompaction(UUID session, @Nullable Duration retryAfter) {
        summaries.release(session, retryAfter);
    }

    /** Saves a summary written after the question {@code parent}, covering the branch up to {@code cutoff}. */
    @Transactional
    public void saveSummary(TenantId tenant, UUID session, UUID parent, UUID cutoff, String content, int tokenCount,
                            @Nullable UUID modelConfigurationId) {
        summaries.insert(tenant, session, parent, cutoff, content, tokenCount, modelConfigurationId);
    }

    private void record(Usage call) {
        if (usage == null || !call.accounting().used()) return;
        // Naming has no turn and knows only its actor; its Tenant is the actor's active one.
        var tenant = call.tenant() != null ? call.tenant() : tenants.findActiveTenant(call.actor()).orElse(null);
        if (tenant == null) return;
        var usageCall = call.call(tenant, Instant.now());
        if (usageCall != null) usage.record(usageCall);
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
