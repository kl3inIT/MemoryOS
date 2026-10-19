package io.memoryos.chat;

import com.embabel.common.ai.prompt.PromptContributor;
import io.memoryos.ai.TurnFailure;
import io.memoryos.ai.TurnFailureException;
import io.memoryos.ai.ModelTurns;
import io.memoryos.ai.ModelAccounting;
import io.memoryos.ai.ModelBinding;
import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.research.ResearchProperties;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.execution.ChatCompactions;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.prompts.AgentBuilderPrompt;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.tools.AgentBuilderTools;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.chat.grounding.ChatGuardrailCheck;
import io.memoryos.chat.grounding.CitationGate;
import io.memoryos.chat.grounding.GroundingClassifier;
import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.ModelFlow;
import io.memoryos.chat.web.WebConnectionService;
import io.memoryos.iam.IamCapability;
import io.memoryos.mcp.McpTurnService;
import io.memoryos.mcp.McpTurnTools;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageLimitException;
import io.memoryos.usage.AiUsageLimitService;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.response.Usage;
import io.memoryos.shared.ActorId;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.streaming.ToolApproval;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import reactor.core.publisher.Sinks;

/**
 * Background turn lifetime is independent of HTTP request/reader lifetime. Each process that executes turns has one,
 * with its own bounds; a run left by a process that died ends when its lease lapses, in whichever process reconciles.
 */
public final class ChatTurnService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatTurnService.class);
    /** How long a tool call waits for the person's approval, as Onyx Craft parks a request at its proxy (MEM-253). */
    static final Duration APPROVAL_WAIT = Duration.ofMinutes(10);
    private final ChatTurnPersistence persistence;
    private final ChatModelExecutor model;
    private final ChatModelSelector models;
    private final @Nullable WebConnectionService web;
    private final @Nullable ImageConnectionService images;
    private final @Nullable AiUsageLimitService spending;
    private final @Nullable ChatSettingsService settings;
    private final @Nullable ResearchProperties research;
    private final @Nullable McpTurnService mcp;
    private final @Nullable ChatGuardrailCheck guardrails;
    private final @Nullable ChatTurnMetrics metrics;
    private final @Nullable ChatCompactions compactions;
    private final @Nullable ChatPersonaService personas;
    private final ChatExecutionProperties limits;
    private final TaskExecutor executor;
    private final StreamBufferWriter streams;
    private final ReentrantLock[] commands = new ReentrantLock[128];
    /** Guards the slots and the queue: who runs, how many of each actor, and who waits, in arrival order. */
    private final ReentrantLock turns = new ReentrantLock();
    private final ArrayDeque<Active> waiting = new ArrayDeque<>();
    private final HashMap<ActorId, Integer> runningOf = new HashMap<>();
    private int running;
    /** Places taken in the queue, counting an admitted run that has not reached it yet. */
    private int queued;
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models) {
        this(persistence, model, limits, executor, streams, models, null, null, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings) {
        this(persistence, model, limits, executor, streams, models, web, images, settings, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings,
            @Nullable ResearchProperties research) {
        this(persistence, model, limits, executor, streams, models, web, images, settings, research, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings,
            @Nullable ResearchProperties research,
            @Nullable McpTurnService mcp) {
        this(persistence, model, limits, executor, streams, models, web, images, settings, research, mcp, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings,
            @Nullable ResearchProperties research,
            @Nullable McpTurnService mcp,
            @Nullable AiUsageLimitService spending) {
        this(persistence, model, limits, executor, streams, models, web, images, settings, research, mcp, spending, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings,
            @Nullable ResearchProperties research,
            @Nullable McpTurnService mcp,
            @Nullable AiUsageLimitService spending, @Nullable ChatGuardrailCheck guardrails) {
        this(persistence, model, limits, executor, streams, models, web, images, settings, research, mcp, spending, guardrails, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings,
            @Nullable ResearchProperties research,
            @Nullable McpTurnService mcp,
            @Nullable AiUsageLimitService spending, @Nullable ChatGuardrailCheck guardrails,
            @Nullable ChatTurnMetrics metrics) {
        this(persistence, model, limits, executor, streams, models, web, images, settings, research, mcp, spending,
                guardrails, metrics, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings,
            @Nullable ResearchProperties research,
            @Nullable McpTurnService mcp,
            @Nullable AiUsageLimitService spending, @Nullable ChatGuardrailCheck guardrails,
            @Nullable ChatTurnMetrics metrics, @Nullable ChatCompactions compactions) {
        this(persistence, model, limits, executor, streams, models, web, images, settings, research, mcp, spending,
                guardrails, metrics, compactions, null);
    }

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelSelector models,
            @Nullable WebConnectionService web,
            @Nullable ImageConnectionService images, @Nullable ChatSettingsService settings,
            @Nullable ResearchProperties research,
            @Nullable McpTurnService mcp,
            @Nullable AiUsageLimitService spending, @Nullable ChatGuardrailCheck guardrails,
            @Nullable ChatTurnMetrics metrics, @Nullable ChatCompactions compactions,
            @Nullable ChatPersonaService personas) {
        this.personas = personas;
        this.compactions = compactions;
        this.guardrails = guardrails;
        this.metrics = metrics;
        this.spending = spending;
        this.research = research;
        this.persistence = persistence;
        this.model = model;
        this.models = models;
        this.web = web;
        this.images = images;
        this.mcp = mcp;
        this.settings = settings;
        this.limits = limits;
        this.executor = executor;
        this.streams = streams;
        Arrays.setAll(commands, ignored -> new ReentrantLock());
    }

    public record Accepted(UUID userMessageId, UUID assistantMessageId, @Nullable UUID modelConfigurationId, @Nullable String fallbackReason) {}
    public record Cancellation(UUID assistantMessageId, ChatMessage.Status status) {}


    /** The browser invokes this after the first completed exchange, never in the answer stream. */
    public void generateTitle(ActorId actor, UUID session) {
        if (!accepting.get() || !occupyForNaming()) return;
        try {
            var input = persistence.claimTitle(actor, session);
            if (input.isEmpty()) return;
            // A spent budget leaves the session's short title rather than failing it, as Onyx does.
            if (spending != null) {
                try { spending.enforce(actor); }
                catch (AiUsageLimitException refused) { return; }
            }
            try (var selected = models.resolveFlow(actor, session, ModelFlow.CHAT_NAMING)) {
                // The callback records usage even when naming fails.
                var title = model.generateTitle(selected.binding(), input.orElseThrow().messages(), accounting -> recordNaming(actor, selected, accounting));
                persistence.completeTitle(actor, input.orElseThrow(), title);
            } catch (RuntimeException failure) {
                // Preserve the initial short title. Never log conversation/provider payloads.
                LOG.atWarn().addKeyValue("event", "chat.naming.failed").addKeyValue("session_id", session)
                        .addKeyValue("error_type", failure.getClass().getName()).log("Chat naming unavailable");
            }
        } finally { vacate(null); }
    }

    private void recordNaming(ActorId actor, ModelResolver.Resolved selected, ModelAccounting accounting) {
        try {
            persistence.recordUsage(new ChatTurnPersistence.Usage(null, actor, AiUsageFlow.CHAT_NAMING, selected.modelConfigurationId(),
                    selected.provenance(), selected.binding().service().getName(), accounting));
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "chat.naming.usage_not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Chat naming usage not recorded");
        }
    }

    public Accepted send(ActorId actor, UUID session, UUID parent, UUID request, String text, @Nullable UUID modelConfigurationId) {
        return command(actor, session, ChatCommand.builder(ChatCommand.Operation.SEND, parent, request, text)
                .modelConfigurationId(modelConfigurationId)
                .build());
    }

    public Accepted command(ActorId actor, UUID session, ChatCommand command) {
        return command(actor, session, command, null);
    }

    /**
     * A command whose turn runs the assistant's unsaved settings instead of the saved ones (MEM-273): allowed in a
     * temporary conversation only, to a person who may edit its assistant, with the settings checked as Save checks
     * them. Nothing of the draft is stored.
     */
    public Accepted command(ActorId actor, UUID session, ChatCommand command, ChatPersonaService.@Nullable PersonaInput draft) {
        return command(actor, session, command, draft, false);
    }

    /**
     * MEM-280: with {@code builder}, the turn is the Agent Builder's: on the draft's temporary conversation, for a
     * person who may edit its assistant, it runs the builder's prompt on the {@code AGENT_BUILDER} task model with only
     * the builder's tools, and every accepted change goes to the browser as an {@code assistant_change} event. The
     * assistant itself is never written.
     */
    public Accepted command(ActorId actor, UUID session, ChatCommand command, ChatPersonaService.@Nullable PersonaInput draft,
                            boolean builder) {
        persistence.require(actor, IamCapability.CHAT_WRITE);
        if (command.image() != ImageMode.off) persistence.require(actor, IamCapability.IMAGE_GENERATE);
        var lock = commandLock(session);
        Admission admission;
        lock.lock();
        try { admission = admit(actor, session, command, draft, builder); }
        finally { lock.unlock(); }
        // The run is registered before the lock is released, so Stop, subscribe and delete see it from here on.
        var run = admission.run();
        return run == null ? admission.accepted() : start(actor, command, admission, run);
    }

    /**
     * A replayed or newly registered turn; {@code run} is null when nothing remains to start. {@code attachedMcp} are
     * the assistant's MCP servers the turn reports when the person cannot use them (MEM-273).
     */
    private record Admission(Accepted accepted, @Nullable Active run, boolean opensMcp, List<UUID> attachedMcp) {
        Admission(Accepted accepted) { this(accepted, null, false, List.of()); }
    }

    /**
     * Network work runs here, after the session lock: opening MCP tools may refresh OAuth tokens. The registered run
     * owns its slot or its place in the queue, so every failure settles it through the same terminal path as a failed
     * execution.
     */
    private Accepted start(ActorId actor, ChatCommand command, Admission admission, Active run) {
        String failure = "CHAT_SETUP_FAILED";
        boolean dispatched = false;
        try {
            // Credentials and OAuth refresh settle here, so the model never waits on an authorization server.
            // A Stop that arrives meanwhile cancels the run; execution then settles it without calling the model.
            if (admission.opensMcp()) {
                var tools = Objects.requireNonNull(mcp).open(actor, command.mcpServerIds(), admission.attachedMcp());
                tools.approvals((binding, callId, arguments) ->
                        awaitApproval(run, binding, callId, arguments, tools.alwaysAllowable(binding)));
                run.setup = run.setup.withMcp(tools);
                // MEM-273: the servers the person can connect show a Connect card under the answer, live and in history.
                var connectable = tools.unavailable().stream().filter(McpTurnTools.Unavailable::connectable)
                        .map(server -> new ChatActivity.UnavailableApp(server.serverId(), server.serverName(),
                                server.reason() == McpTurnTools.Unavailable.Reason.NOT_CONNECTED
                                        ? ChatActivity.UnavailableApp.Reason.NOT_CONNECTED
                                        : ChatActivity.UnavailableApp.Reason.REAUTH_REQUIRED)).toList();
                if (!connectable.isEmpty()) {
                    run.recorder.unavailableApps(connectable);
                    streams.unavailableApps(run.setup.assistantMessageId(), connectable);
                }
            }
            failure = "CHAT_SUBMIT_FAILED";
            if (!accepting.get()) throw ChatException.busy();
            if (run.slot) executor.execute(() -> execute(run));
            else enqueue(run);
            dispatched = true;
            return admission.accepted();
        } finally {
            if (!dispatched) {
                run.finish(ChatMessage.Status.FAILED, failure);
                finalizeRun(run);
                retireWhenDrained(run);
            }
        }
    }

    private Admission admit(ActorId actor, UUID session, ChatCommand command, ChatPersonaService.@Nullable PersonaInput draft,
                            boolean builder) {
        var previous = persistence.existing(actor, session, command);
        if (previous.isPresent()) return new Admission(accepted(previous.orElseThrow()));
        if (builder && (draft == null || command.deepResearch() || command.webSearch() != WebSearchMode.off
                || command.image() != ImageMode.off || !command.mcpServerIds().isEmpty() || !command.fileIds().isEmpty()))
            throw ChatException.invalid("An Agent Builder turn carries the assistant's unsaved settings and nothing else.");
        // Departure from Onyx, which only hides its button: a disabled mode must not run through the public API.
        if (command.deepResearch() && (settings == null || !settings.read(actor).deepResearchEnabled()))
            throw ChatException.researchUnavailable();
        // Onyx attaches tools to the agent: a command may only use tools the session agent allows.
        var agent = draft == null ? persistence.agent(actor, session, command.channel() != null)
                : draftAgent(actor, session, draft);
        // MEM-280: the builder checks the draft as the test chat does, then runs as itself, never as the assistant.
        BuilderAdmission building = builder ? builderAdmission(actor, session, Objects.requireNonNull(draft), agent) : null;
        if (building != null) agent = building.agent();
        var tools = agent.persona();
        // MEM-195: answers from documents only are refused Deep research and, unless allowed, Web search.
        // The builder answers no question from documents, so the grounded-answer policy does not apply to it; the
        // Tenant's guardrails do, as on every turn: its messages and replies are checked as an ordinary turn's are.
        var tenantPolicy = settings == null ? ChatSettingsService.TurnPolicy.NONE : settings.turnPolicy(actor);
        var policy = building == null ? tenantPolicy
                : new ChatSettingsService.TurnPolicy(false, false, tenantPolicy.guardrails());
        boolean grounded = tools.options().grounded();
        if (grounded && command.deepResearch()) throw ChatException.researchUnavailable();
        if (grounded && command.webSearch() != WebSearchMode.off && !policy.groundedAllowWeb()) throw ChatException.webUnavailable();
        if (command.webSearch() != WebSearchMode.off && !tools.tools().contains("web_search")) throw ChatException.webUnavailable();
        if (command.image() != ImageMode.off && !tools.tools().contains("image_generation")) throw ChatException.providerUnavailable();
        if (tools.mcpServerIds() != null && !tools.mcpServerIds().containsAll(command.mcpServerIds()))
            throw ChatException.providerUnavailable();
        // MEM-123: what the Tenant, the Group and the person may spend, weighed before a provider is chosen.
        if (spending != null) spending.enforce(actor);
        if (!accepting.get()) throw ChatException.busy();
        boolean slot = claim(actor);
        ChatTurnPersistence.Reservation reserved = null;
        ModelResolver.Resolved resolved = null;
        boolean transferred = false;
        try {
            resolved = building != null ? models.resolveFlow(actor, session, ModelFlow.AGENT_BUILDER)
                    : models.resolve(actor, session, command.modelConfigurationId(), agent);
            var binding = resolved.binding();
            if (building != null && !binding.toolCalling()) throw ChatException.builderModelUnsupported();
            if (grounded && !binding.toolCalling()) throw ChatException.groundedModelUnsupported();
            if (command.deepResearch()) {
                // As Onyx: not in Project chats and at least 50,000 input tokens; research agents need tool calling.
                int minimum = research == null ? 50_000 : research.minimumContextTokens();
                // MEM-130: a model that cannot research is not the organization's setting, so it has its own code.
                if (agent.inProject()) throw ChatException.researchUnavailable();
                if (!binding.toolCalling() || binding.contextWindow() < minimum) throw ChatException.researchModelUnsupported();
            }
            // The date is written once, by ChatPrompts.resolve; Embabel's CurrentDate would repeat it.
            String contribution = binding.service().getPromptContributors().stream().map(PromptContributor::contribution)
                    .filter(value -> !value.isBlank()).collect(Collectors.joining("\n----\n"));
            var webAccess = new WebConnectionService.Access(null, null);
            if (command.webSearch() != WebSearchMode.off) {
                // Provider-hosted search needs no external connection; external search needs one.
                boolean nativeSearch = binding.service().getChatModel() instanceof ModelTurns turns && turns.nativeWebSearch();
                // Research agents always search through the Web tools, as Onyx does, even when the model hosts search.
                boolean externalSearch = !nativeSearch || command.deepResearch();
                if (!binding.toolCalling() || (externalSearch && web == null)) {
                    LOG.atWarn().addKeyValue("event", "chat.web_search.rejected")
                            .addKeyValue("model_configuration_id", resolved.modelConfigurationId())
                            .addKeyValue("tool_calling", binding.toolCalling()).addKeyValue("native_search", nativeSearch)
                            .addKeyValue("web_connections", web != null).addKeyValue("error_code", "CHAT_WEB_UNAVAILABLE")
                            .log("Web search rejected for the model");
                    throw ChatException.webUnavailable();
                }
                if (externalSearch) {
                    webAccess = web.resolve(actor);
                    if (webAccess.search() == null) {
                        LOG.atWarn().addKeyValue("event", "chat.web_search.rejected")
                                .addKeyValue("model_configuration_id", resolved.modelConfigurationId())
                                .addKeyValue("reason", "no_search_connection").addKeyValue("error_code", "CHAT_WEB_UNAVAILABLE")
                                .log("Web search rejected: no active usable search connection");
                        throw ChatException.webUnavailable();
                    }
                }
            }
            var imageAccess = new ImageConnectionService.Access(null);
            if (command.image() != ImageMode.off) {
                if (!binding.toolCalling() || images == null) throw ChatException.providerUnavailable();
                imageAccess = images.resolve(actor);
                if (imageAccess.generate() == null) throw ChatException.providerUnavailable();
            }
            int contextLimit = Math.min(limits.contextCap(), binding.inputLimit(limits.maxOutputTokens()));
            reserved = persistence.reserve(actor, session, command, limits.leaseTtl(), contextLimit,
                    new ChatTurnPersistence.ModelSelection(command.modelConfigurationId(), resolved.modelConfigurationId(),
                            resolved.fallbackReason(), binding, resolved.contextRevision(), contribution, agent));
            if (!reserved.created()) return new Admission(accepted(reserved));
            var context = reserved.context() != null ? reserved.context() : persistence.loadContext(actor, session, reserved);
            // Deep research reads the whole window it can fit; compaction covers ordinary turns only (MEM-268). Its helper
            // calls offer no tools, so it replays no earlier tool calls either (MEM-287).
            var replayed = command.deepResearch() ? context.withoutSummary().withoutToolCalls() : context;
            var setup = ChatTurnSetup.resolve(session, reserved.assistantMessageId(), replayed, contextLimit, binding, contribution)
                    .withWeb(command.webSearch(), webAccess).withImage(command.image(), imageAccess);
            // Deep research runs its own agents and tool set, so selected MCP servers apply only to ordinary turns.
            boolean selectsMcp = !command.mcpServerIds().isEmpty() && !command.deepResearch();
            if (selectsMcp && (!binding.toolCalling() || mcp == null)) throw ChatException.providerUnavailable();
            // MEM-273: the composer offers only servers the person can use, so the assistant's others are resolved here to
            // tell the model and the person. A channel has no Connect card, and Deep research uses no MCP server.
            List<UUID> attachedMcp = command.channel() == null && !command.deepResearch() && binding.toolCalling()
                    && mcp != null && tools.mcpServerIds() != null ? List.copyOf(tools.mcpServerIds()) : List.of();
            boolean opensMcp = selectsMcp || !attachedMcp.isEmpty();
            if (command.deepResearch()) setup = setup.withResearch(researchState(context, setup));
            if (building != null) {
                UUID reply = setup.assistantMessageId();
                setup = setup.withBuilder(new AgentBuilderTools.Turn(building.draft(), building.choices(), building.check(),
                        (call, patch) -> streams.assistantChange(reply, call, patch)));
            }
            // Check 1 fails closed: a turn that needs it is not started without it.
            if (ChatGuardrailCheck.applies(setup, policy) && guardrails == null) throw ChatException.providerUnavailable();
            var run = new Active(setup, resolved, policy, question(context), earlier(context), context.uiLanguage(), actor, slot);
            run.historyBudget = ChatTurnSetup.historyBudget(setup, contextLimit);
            streams.open(setup.assistantMessageId());
            active.put(setup.assistantMessageId(), run);
            transferred = true;
            return new Admission(accepted(reserved), run, opensMcp, attachedMcp);
        } catch (RuntimeException failure) {
            if (!transferred) {
                // Setup errors still own a reserved row; finish it without starting the model.
                if (reserved != null && reserved.created()) persistence.finish(session, reserved.assistantMessageId(),
                        ChatMessage.Status.FAILED, "", "CHAT_SETUP_FAILED", null, null, null, null);
            }
            throw failure;
        } finally {
            if (!transferred) {
                if (resolved != null) resolved.close();
                unclaim(actor, slot);
            }
        }
    }

    /** The builder's agent for one turn, what its editor may choose and Save's check of a proposed form. */
    private record BuilderAdmission(ChatTurnPersistence.SessionAgent agent, ChatPersonaService.PersonaInput draft,
                                    ChatPersonaService.BuilderChoices choices, Consumer<ChatPersonaService.PersonaInput> check) {}

    /**
     * The Agent Builder as the turn's agent: its own prompt with the form and the choices, none of the assistant's
     * documents, files, tools or MCP servers, and the saved assistant's revision so the reservation's recheck holds.
     */
    private BuilderAdmission builderAdmission(ActorId actor, UUID session, ChatPersonaService.PersonaInput draft,
                                              ChatTurnPersistence.SessionAgent tested) {
        var service = Objects.requireNonNull(personas);
        UUID assistant = persistence.testedAssistant(actor, session);
        var choices = service.builderChoices(actor, assistant);
        var persona = new JdbcChatRepository.Persona(
                AgentBuilderPrompt.system(draft, choices, AgentBuilderTools.LISTED_OPTIONS),
                ChatTurnOptions.builder().searchEnabled(false).codeInterpreter(false).build(),
                tested.persona().revision(), null, List.of(), Set.of(), List.of(), null);
        return new BuilderAdmission(new ChatTurnPersistence.SessionAgent(persona, tested.agentsManage(),
                tested.modelsManage(), tested.inProject()), draft, choices, input -> service.draft(actor, assistant, input));
    }

    private ChatTurnPersistence.SessionAgent draftAgent(ActorId actor, UUID session, ChatPersonaService.PersonaInput draft) {
        if (personas == null) throw ChatException.unavailable();
        return persistence.agent(actor, session, personas.draft(actor, persistence.testedAssistant(actor, session), draft));
    }

    /**
     * Takes a slot for the actor's turn, or a place in the queue when the process or the actor is at its bound;
     * returns whether it was a slot. A free slot never passes a run that could take it: every release promotes first.
     */
    private boolean claim(ActorId actor) {
        turns.lock();
        try {
            if (running < limits.concurrency() && runningOf.getOrDefault(actor, 0) < limits.perActorConcurrency()) {
                running++;
                runningOf.merge(actor, 1, Integer::sum);
                return true;
            }
            if (queued >= limits.maxWaiting()) throw ChatException.busy();
            queued++;
            return false;
        } finally { turns.unlock(); }
    }

    /** Gives back what an admission took for a run that was never registered. */
    private void unclaim(ActorId actor, boolean slot) {
        if (slot) vacate(actor);
        else {
            turns.lock();
            try { queued--; }
            finally { turns.unlock(); }
        }
    }

    /** Naming takes a free slot or is skipped; it never waits and never counts against a person. */
    private boolean occupyForNaming() {
        turns.lock();
        try {
            if (running >= limits.concurrency()) return false;
            running++;
            return true;
        } finally { turns.unlock(); }
    }

    /** Frees a slot, of an actor's turn or of naming, and starts the runs that were waiting for it. */
    private void vacate(@Nullable ActorId actor) {
        List<Active> starting;
        turns.lock();
        try {
            running--;
            if (actor != null) runningOf.computeIfPresent(actor, (_, count) -> count == 1 ? null : count - 1);
            starting = promote();
        } finally { turns.unlock(); }
        dispatch(starting);
    }

    /** The run joins the queue and its stream says so; a slot freed since admission is taken at once. */
    private void enqueue(Active run) {
        List<Active> starting;
        boolean ended;
        turns.lock();
        try {
            run.waitingSince = System.nanoTime();
            // Stop or deletion may have arrived while tools opened, before anything could settle a run that was not
            // queued yet; a deleted conversation's stream is gone. Such a run gives its place back and never queues.
            ended = run.deleted || run.stopReason.get() != null;
            if (ended) {
                queued--;
                run.place = false;
            } else {
                waiting.addLast(run);
                streams.waiting(run.setup.assistantMessageId(), true);
            }
            starting = promote();
        } finally { turns.unlock(); }
        dispatch(starting);
        if (ended) endUnstarted(run, ChatMessage.Status.CANCELED, null);
        // A Stop between the check and here found the run queued or not; settle it if it is still there.
        else if (run.stopReason.get() != null) settleWaiting(run, ChatMessage.Status.CANCELED, null);
    }

    /**
     * Under {@code turns}: gives slots, in arrival order, to the waiting runs the bounds now admit, passing a run of
     * an actor at their bound. It runs in the same hold that freed the slot, so a new arrival cannot take it first.
     */
    private List<Active> promote() {
        var starting = new ArrayList<Active>();
        for (var runs = waiting.iterator(); runs.hasNext() && running < limits.concurrency(); ) {
            var run = runs.next();
            var actor = run.claimant;
            if (runningOf.getOrDefault(actor, 0) >= limits.perActorConcurrency()) continue;
            runs.remove();
            queued--;
            run.place = false;
            running++;
            runningOf.merge(actor, 1, Integer::sum);
            run.slot = true;
            streams.waiting(run.setup.assistantMessageId(), false);
            starting.add(run);
        }
        return starting;
    }

    private void dispatch(List<Active> starting) {
        for (var run : starting) {
            if (metrics != null) metrics.waited(System.nanoTime() - run.waitingSince);
            try { executor.execute(() -> execute(run)); }
            catch (RuntimeException rejected) { endUnstarted(run, ChatMessage.Status.FAILED, "CHAT_SUBMIT_FAILED"); }
        }
    }

    /**
     * Ends a run that is still in the queue, without the model: stopped, deleted, interrupted or past its wait.
     * Returns false for a run that has a slot, which ends through its own execution.
     */
    private boolean settleWaiting(Active run, ChatMessage.Status status, @Nullable String failure) {
        turns.lock();
        try {
            if (!waiting.remove(run)) return false;
            queued--;
            run.place = false;
        } finally { turns.unlock(); }
        endUnstarted(run, status, failure);
        return true;
    }

    /** The terminal path of a run the model never ran for. */
    private void endUnstarted(Active run, ChatMessage.Status status, @Nullable String failure) {
        run.finish(status, failure);
        measure(run);
        finalizeRun(run);
        retireWhenDrained(run);
    }

    public Cancellation cancel(ActorId actor, UUID session, UUID assistant) {
        persistence.require(actor, IamCapability.CHAT_WRITE);
        var lock = commandLock(session);
        lock.lock();
        try {
            var status = persistence.authorizeReply(actor, session, assistant);
            var run = active.get(assistant);
            if (run != null && status == ChatMessage.Status.RUNNING) {
                run.cancel(StopReason.USER);
                settleWaiting(run, ChatMessage.Status.CANCELED, null);
            }
            return new Cancellation(assistant, status);
        } finally { lock.unlock(); }
    }

    /**
     * The person's answer to a call that waits for approval (MEM-253). Only the reply's owner may answer; the decision
     * reaches the run on this process, which is the one that runs every turn.
     */
    public void decide(ActorId actor, UUID session, UUID assistant, String callId, boolean approved) {
        decide(actor, session, assistant, callId, approved, false);
    }

    /**
     * As {@link #decide(ActorId, UUID, UUID, String, boolean)}; {@code alwaysAllow} also sets the tool to run without
     * asking from now on (MEM-267), which needs MCP administration and a tool that is not destructive. The policy is
     * saved before the call is approved, so a refused change leaves the call waiting for another answer.
     */
    public void decide(ActorId actor, UUID session, UUID assistant, String callId, boolean approved, boolean alwaysAllow) {
        persistence.require(actor, IamCapability.CHAT_WRITE);
        var lock = commandLock(session);
        lock.lock();
        try {
            var status = persistence.authorizeReply(actor, session, assistant);
            var run = active.get(assistant);
            var pending = run == null || status != ChatMessage.Status.RUNNING ? null : run.approvals.get(callId);
            if (pending == null || pending.isDone()) throw ChatException.approvalNotPending();
            if (alwaysAllow && approved) {
                var binding = run.approvalBindings.get(callId);
                if (binding == null || mcp == null) throw ChatException.approvalNotPending();
                mcp.allowAlways(actor, binding);
            }
            if (!pending.complete(approved ? McpTurnTools.Decision.APPROVED : McpTurnTools.Decision.DECLINED))
                throw ChatException.approvalNotPending();
        } finally { lock.unlock(); }
    }

    /** Publishes the call to the person and waits for their answer, a Stop, or {@link #APPROVAL_WAIT}. */
    private McpTurnTools.Decision awaitApproval(Active run, McpTurnTools.Binding binding, String callId, String arguments,
                                                boolean alwaysAllowable) {
        run.check();
        var pending = new CompletableFuture<McpTurnTools.Decision>();
        if (run.approvals.putIfAbsent(callId, pending) != null) return McpTurnTools.Decision.DECLINED;
        run.approvalBindings.put(callId, binding);
        UUID reply = run.setup.assistantMessageId();
        McpTurnTools.Decision decision;
        try {
            streams.approval(reply, callId, binding.serverName(), binding.toolName(), binding.title(), arguments, null, alwaysAllowable);
            // A Stop between registering and publishing would otherwise leave the call waiting for the full period.
            if (run.stopReason.get() != null) pending.complete(McpTurnTools.Decision.DECLINED);
            try {
                decision = pending.get(APPROVAL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException expired) {
                decision = McpTurnTools.Decision.NO_DECISION;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                decision = McpTurnTools.Decision.DECLINED;
            } catch (ExecutionException | CancellationException unexpected) {
                decision = McpTurnTools.Decision.DECLINED;
            }
        } finally {
            run.approvals.remove(callId, pending);
            run.approvalBindings.remove(callId, binding);
        }
        streams.approval(reply, callId, binding.serverName(), binding.toolName(), binding.title(), arguments,
                ToolApproval.Decision.valueOf(decision.name()), false);
        LOG.atInfo().addKeyValue("event", "chat.tool_approval.decided").addKeyValue("message_id", reply)
                .addKeyValue("mcp_server_id", binding.serverId()).addKeyValue("tool", binding.modelName())
                .addKeyValue("decision", decision.name()).log("Tool approval decided");
        return decision;
    }

    public Supplier<StreamBufferWriter.Reader> subscribe(ActorId actor, UUID session, UUID assistant, long after) {
        persistence.require(actor, IamCapability.CHAT_READ);
        var lock = commandLock(session);
        lock.lock();
        try {
            persistence.authorizeReply(actor, session, assistant);
            streams.validateSubscription(assistant, after);
            // Authorization/cursor errors remain synchronous; no reader slot is held until subscription.
            return () -> {
                lock.lock();
                try {
                    persistence.authorizeReply(actor, session, assistant);
                    // The reader re-authorizes on every liveness check, so a revoked membership ends a long stream.
                    return streams.subscribe(assistant, after, () -> {
                        persistence.require(actor, IamCapability.CHAT_READ);
                        return persistence.authorizeReply(actor, session, assistant) == ChatMessage.Status.RUNNING;
                    });
                }
                finally { lock.unlock(); }
            };
        } finally { lock.unlock(); }
    }

    private ReentrantLock commandLock(UUID session) { return commands[Math.floorMod(session.hashCode(), commands.length)]; }

    public void delete(ActorId actor, UUID session) {
        var lock = commandLock(session);
        lock.lock();
        try {
            var messages = persistence.delete(actor, session);
            for (UUID message : messages) {
                var run = active.get(message);
                if (run != null) {
                    run.deleted = true;
                    run.cancel(StopReason.USER);
                    settleWaiting(run, ChatMessage.Status.CANCELED, null);
                }
            }
            streams.discard(messages);
        } finally { lock.unlock(); }
    }

    /**
     * Onyx "Delete All Chats": every owned conversation goes exactly as deleting it one by one would, stopping its
     * active reply. A conversation removed meanwhile is skipped. Returns how many were deleted.
     */
    public int deleteAll(ActorId actor) {
        int deleted = 0;
        for (UUID session : persistence.ownedSessions(actor)) {
            try {
                delete(actor, session);
                deleted++;
            } catch (ChatException gone) {
                if (!"CHAT_UNAVAILABLE".equals(gone.code())) throw gone;
            }
        }
        return deleted;
    }

    private static ChatTurnSetup.Research researchState(ChatTurnPersistence.TurnContext context, ChatTurnSetup setup) {
        // Onyx skips clarification when the previous assistant message was a clarification question.
        boolean skip = context.newestFirst().stream().filter(message -> message.role() == ChatMessage.Role.ASSISTANT).findFirst()
                .map(message -> message.research().clarification()).orElse(false);
        var files = new LinkedHashMap<UUID, ChatFileDescriptor>();
        Stream.concat(context.workspaceFiles().stream(), context.newestFirst().stream().flatMap(message -> message.files().stream()))
                .filter(file -> setup.fileIds().contains(file.id())).forEach(file -> files.putIfAbsent(file.id(), file));
        return new ChatTurnSetup.Research(true, skip, context.uiLanguage(), List.copyOf(files.values()));
    }

    private static Accepted accepted(ChatTurnPersistence.Reservation reservation) {
        return new Accepted(reservation.userMessageId(), reservation.assistantMessageId(), reservation.modelConfigurationId(), reservation.fallbackReason());
    }

    private void execute(Active run) {
        try {
            run.check();
            if (!checkGuardrails(run)) return;
            model.execute(run.setup, run::check, run.cancellation.asMono(),
                    text -> show(run, run.gate == null ? text : run.gate.accept(text)),
                    accounting -> run.accounting = accounting, event -> {
                        // A step follows all the text written before it, and the model's next text starts a paragraph.
                        if (event instanceof ChatToolEvent tool && tool.parentToolCallId() == null) {
                            if (run.gate != null) show(run, run.gate.flush());
                            run.stepped = true;
                        }
                        run.activity(event);
                        switch (event) {
                            case ChatToolEvent tool -> streams.tool(run.setup.assistantMessageId(), shown(run, tool));
                            case ChatReasoningDelta reasoning -> streams.reasoning(run.setup.assistantMessageId(), reasoning.text(), reasoning.parentToolCallId());
                            case ChatResearchEvent researchEvent -> streams.research(run.setup.assistantMessageId(), researchEvent);
                        }
                    }, imageEvent -> streams.image(run.setup.assistantMessageId(), imageEvent),
                    codeEvent -> streams.code(run.setup.assistantMessageId(), codeEvent),
                    draining -> run.draining = draining);
            run.check();
            if (run.gate != null) settle(run);
            if (run.content.isEmpty()) throw TurnFailure.EMPTY_RESPONSE.exception();
            run.finish(ChatMessage.Status.COMPLETED, null);
        } catch (RuntimeException failure) {
            boolean userStop = run.stopReason.get() == StopReason.USER;
            String code = run.stopReason.get() == StopReason.INTERRUPTED ? "CHAT_INTERRUPTED"
                    : failureCode(failure, run.setup.binding());
            run.finish(userStop ? ChatMessage.Status.CANCELED : ChatMessage.Status.FAILED,
                    userStop ? null : code);
            // Provider exceptions may contain prompts/credentials. Never log their payload or stack here.
            if (!userStop) LOG.atWarn().addKeyValue("event", "chat.run.failed").addKeyValue("message_id", run.setup.assistantMessageId())
                    .addKeyValue("error_code", code).addKeyValue("error_type", failure.getClass().getName()).log("Chat run failed");
        } finally {
            // Also close the product lifecycle if framework linkage or another Error escapes the task.
            run.finish(ChatMessage.Status.FAILED, "CHAT_EXECUTION_FAILED");
            measure(run);
            finalizeRun(run);
            retireWhenDrained(run);
        }
    }

    private void show(Active run, String text) {
        if (text.isEmpty()) return;
        String shown = run.paragraph(text);
        run.append(shown, limits.maxAnswerCharacters());
        streams.append(run.setup.assistantMessageId(), shown);
        firstText(run);
    }

    private void firstText(Active run) {
        if (metrics != null && run.firstTextShown.compareAndSet(false, true))
            metrics.firstText(run.grounded, System.nanoTime() - run.admitted);
    }

    private void measure(Active run) {
        var outcome = run.outcome;
        if (metrics == null || outcome == null) return;
        try {
            metrics.turn(outcome.status(), outcome.failure(), outcome.refusal(), run.grounded, run.setup.research().enabled(),
                    System.nanoTime() - run.admitted);
        } catch (RuntimeException failure) {
            // Telemetry never changes a turn's outcome.
            LOG.atWarn().addKeyValue("event", "chat.metrics.not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Chat turn metrics not recorded");
        }
    }

    /**
     * MEM-195 Check 1, before the answer model: a blocked question ends the turn with the Tenant's message; a
     * conversational message in grounded mode is answered normally. Returns whether the answer model runs.
     */
    private boolean checkGuardrails(Active run) {
        if (guardrails == null || !ChatGuardrailCheck.applies(run.setup, run.policy)) return true;
        long started = System.nanoTime();
        // One call, on the Tenant's System One connection (MEM-198), else on the check's own task model, else the
        // conversation's model. It is not asked again on another classifier: when it gives no verdict, the answer
        // model below still carries the rules, as no guardrail project falls back to a second classifier either.
        ChatGuardrailCheck.Result result = null;
        String classifier = "llm";
        try {
            var connection = guardrails.connection(run.setup.tenant());
            if (connection != null) {
                classifier = "system_one";
                result = guardrails.checkOn(connection, run.setup, run.question, run.earlier, run.policy,
                        usage -> recordCheck(run, connection, usage));
            } else try (var selected = models.resolveFlow(run.setup.actor(), run.setup.sessionId(), ModelFlow.CHAT_GUARDRAIL)) {
                result = guardrails.check(selected.binding(), run.setup, run.question, run.earlier, run.policy,
                        accounting -> recordCheck(run, selected.modelConfigurationId(), selected.provenance(),
                                selected.binding().service().getName(), accounting));
            }
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            checkFailed(run, failure);
        }
        run.check();
        // A guardrail never becomes a technical error, and the person is not told about one: a turn without a verdict
        // is answered. Whatever the verdict, the model that answers carries the blocked topics itself (MEM-208), so it
        // declines one with the Tenant's message even when this check misread the message or was not reached.
        if (metrics != null)
            metrics.guardrail(result == null ? "unchecked" : result.kind().name().toLowerCase(Locale.ROOT), classifier,
                    result != null && result.review(),
                    System.nanoTime() - started);
        run.setup = run.setup.withOptions(run.setup.options()
                .withTopicRules(ChatGuardrailCheck.rulesForTheAnswerModel(run.policy)));
        if (result != null && result.kind() == ChatGuardrailCheck.Kind.BLOCKED) {
            guardrails.recordBlock(run.setup.tenant(), run.setup.actor(), run.setup.sessionId(), result, null);
            refuse(run, ChatMessage.BLOCKED_TOPIC, Objects.requireNonNull(result.message()));
            run.finish(ChatMessage.Status.COMPLETED, null);
            return false;
        }
        var options = run.setup.options();
        if (result != null && result.kind() == ChatGuardrailCheck.Kind.CONVERSATIONAL && options.grounded())
            run.setup = run.setup.withOptions(options.withGrounded(false));
        boolean grounded = run.setup.options().grounded();
        var rules = run.policy.guardrails();
        if (grounded || !rules.blockedPhrases().isEmpty())
            run.gate = new CitationGate(grounded, id -> id >= 1 && id <= run.sourceCount(), rules::blockedPhraseIn, rules.longestPhrase());
        return true;
    }

    /** The end of a gated answer: release what is held, or replace an uncited or blocked answer with its refusal. */
    private void settle(Active run) {
        var ending = Objects.requireNonNull(run.gate).finish();
        switch (ending.outcome()) {
            case ANSWERED -> show(run, ending.text());
            case UNCITED -> refuse(run, run.sourceCount() == 0 ? ChatMessage.NO_EVIDENCE : ChatMessage.UNCITED,
                    ChatRefusals.notInDocuments(run.uiLanguage));
            case BLOCKED -> {
                if (guardrails != null) guardrails.recordBlock(run.setup.tenant(), run.setup.actor(), run.setup.sessionId(),
                        new ChatGuardrailCheck.Result(ChatGuardrailCheck.Kind.BLOCKED, null, null, ending.phrase()), null);
                refuse(run, ChatMessage.BLOCKED_TOPIC, run.policy.guardrails().blockedPhraseMessage());
            }
        }
    }

    /** A refusal replaces the stored answer; the stream shows it after anything already released. */
    private void refuse(Active run, String reason, String text) {
        boolean shown = run.refuse(reason, text);
        streams.append(run.setup.assistantMessageId(), shown ? "\n\n" + text : text);
        firstText(run);
    }

    /**
     * The check failed. A spent budget or another reported turn failure still ends the turn; anything else, a refused
     * credential or an unreadable verdict included, is logged and the turn fails open with the rules in the answer.
     */
    private void checkFailed(Active run, RuntimeException failure) {
        var reported = TurnFailureException.reportedIn(failure);
        if (reported.isPresent()) throw reported.get().exception();
        LOG.atWarn().addKeyValue("event", "chat.guardrail.unavailable").addKeyValue("message_id", run.setup.assistantMessageId())
                .addKeyValue("error_type", failure.getClass().getName())
                .log("Chat guardrail check unavailable");
    }

    /**
     * A check on a System One connection, recorded under the connection's name with the tokens the service reported
     * and the cost its input price gives; a service that reports input only wrote no output.
     */
    private void recordCheck(Active run, SystemOneConnectionService.Connection connection, Usage usage) {
        Long input = usage.inputTokens() == null ? null : usage.inputTokens().longValue();
        Long output = usage.outputTokens() != null ? Long.valueOf(usage.outputTokens()) : input == null ? null : Long.valueOf(0);
        recordCheck(run, null, new ModelResolver.Provenance(null, connection.name(), connection.dataBoundary().name()),
                connection.model(), new ModelAccounting(input, output, connection.cost(input), 0, true));
    }

    /** The check is part of the turn's cost, recorded against the model that ran it. */
    private void recordCheck(Active run, @Nullable UUID modelConfigurationId, ModelResolver.Provenance provenance,
            String modelName, ModelAccounting accounting) {
        try {
            persistence.recordUsage(new ChatTurnPersistence.Usage(run.setup.tenant(), run.setup.actor(), AiUsageFlow.CHAT_GUARDRAIL,
                    modelConfigurationId, provenance, modelName, accounting));
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "chat.guardrail.usage_not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Chat guardrail usage not recorded");
        }
    }

    /** The text the person wrote in this turn, without attachments: the newest message of the conversation. */
    private static String question(ChatTurnPersistence.TurnContext context) {
        return context.newestFirst().stream().filter(message -> message.role() == ChatMessage.Role.USER).findFirst()
                .map(ChatMessage::content).orElse("");
    }

    /** The messages before this turn's question, oldest first, that the guardrail check reads as context (MEM-206). */
    private static List<ChatMessage> earlier(ChatTurnPersistence.TurnContext context) {
        var before = new ArrayList<ChatMessage>();
        boolean past = false;
        for (var message : context.newestFirst()) {
            if (!past) {
                past = message.role() == ChatMessage.Role.USER;
                continue;
            }
            if (message.content() == null || message.content().isBlank()) continue;
            before.addFirst(message);
            if (before.size() == GroundingClassifier.EARLIER_MESSAGES) break;
        }
        return before;
    }

    private void retireWhenDrained(Active run) {
        run.draining.whenComplete((_, failure) -> {
            if (failure != null) LOG.atWarn().addKeyValue("event", "chat.run.native_cleanup_failed")
                    .addKeyValue("message_id", run.setup.assistantMessageId())
                    .addKeyValue("error_type", failure.getClass().getName()).log("Chat native cleanup failed");
            try { run.resolved.close(); }
            finally {
                var tools = run.setup.mcp();
                if (tools != null) tools.close();
                run.drained = true;
                // Terminal persistence and resource retirement may finish in either order.
                releaseIfFinished(run);
                run.finished.complete(null);
            }
        });
    }

    private void releaseIfFinished(Active run) {
        if (!run.persisted || !run.drained || !active.remove(run.setup.assistantMessageId(), run)) return;
        if (run.slot) vacate(run.claimant);
        else {
            // It ended before it was given a slot: out of the queue, or before it reached it.
            turns.lock();
            try {
                if (run.place) {
                    waiting.remove(run);
                    queued--;
                    run.place = false;
                }
            } finally { turns.unlock(); }
        }
    }

    /**
     * Only pending outcomes and due lease renewals touch the database; Stop is local. A turn has no total deadline:
     * the lease is renewed while this process runs it, so only a dead process lets it lapse, and the Worker ends it.
     * A waiting run is renewed like a running one and ends here once it has waited {@code waitLimit}.
     */
    public void maintain() {
        long now = System.nanoTime();
        expireWaiting(now);
        var due = new ArrayList<Active>();
        for (var run : active.values()) {
            if (run.outcome != null) { finalizeRun(run); continue; }
            if (now - run.renewedAt >= limits.leaseRenewal().toNanos()) due.add(run);
        }
        if (!due.isEmpty()) {
            try {
                var renewed = persistence.renewLeases(due.stream().map(run -> run.setup.assistantMessageId()).toList(), limits.leaseTtl());
                for (var run : due) {
                    run.renewedAt = now;
                    // The row already ended elsewhere (reconciled after a lapse, or deleted); this process no longer owns it.
                    if (!renewed.contains(run.setup.assistantMessageId())) {
                        run.cancel(StopReason.INTERRUPTED);
                        settleWaiting(run, ChatMessage.Status.FAILED, "CHAT_INTERRUPTED");
                    }
                }
            } catch (RuntimeException failure) {
                // Never stop a live run over a failed renewal; the next tick retries well inside the lease.
                LOG.atWarn().addKeyValue("event", "chat.lease.renewal_failed")
                        .addKeyValue("error_type", failure.getClass().getName()).log("Chat lease renewal unavailable");
            }
        }
        // A turn whose process died is ended by the Worker (ChatRunReconciliation), not here.
    }

    private void expireWaiting(long now) {
        Active[] queue;
        turns.lock();
        try { queue = waiting.toArray(Active[]::new); }
        finally { turns.unlock(); }
        for (var run : queue) {
            if (run.stopReason.get() != null) settleWaiting(run, ChatMessage.Status.CANCELED, null);
            else if (now - run.waitingSince >= limits.waitLimit().toNanos()
                    && settleWaiting(run, ChatMessage.Status.FAILED, ChatException.busy().code()))
                LOG.atWarn().addKeyValue("event", "chat.run.wait_expired").addKeyValue("message_id", run.setup.assistantMessageId())
                        .addKeyValue("error_code", ChatException.busy().code()).log("Chat run waited past its limit");
        }
    }

    /**
     * MEM-275: a view's requests read its row, so the row is written before the browser can show the view. A view
     * that could not be kept is not shown.
     */
    private ChatToolEvent shown(Active run, ChatToolEvent tool) {
        var app = tool.app();
        if (app == null || run.deleted) return tool;
        try {
            persistence.recordToolApp(run.setup.assistantMessageId(), app);
            return tool;
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "chat.tool_app.not_recorded")
                    .addKeyValue("message_id", run.setup.assistantMessageId())
                    .addKeyValue("error_type", failure.getClass().getName()).log("Chat tool view not recorded");
            return tool.withoutApp();
        }
    }

    /**
     * MEM-287: the answer's tool calls, whatever its status, since a stopped turn may already have written files. A
     * failure costs only the replay in later turns, never the answer.
     */
    private void recordToolCalls(Active run) {
        if (run.deleted) return;
        try {
            persistence.recordToolCalls(run.setup.assistantMessageId(), run.setup.transcript().calls(),
                    run.setup.transcript().apps());
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "chat.tool_calls.not_recorded")
                    .addKeyValue("message_id", run.setup.assistantMessageId())
                    .addKeyValue("error_type", failure.getClass().getName()).log("Chat tool calls not recorded");
        }
    }

    private void finalizeRun(Active run) {
        if (!run.finalizing.tryLock()) return;
        try {
            if (active.get(run.setup.assistantMessageId()) != run) return;
            try {
                var outcome = run.outcome;
                if (outcome == null) return;
                if (!run.persisted) {
                    var saved = persistence.finishAndRead(run.setup.sessionId(), run.setup.assistantMessageId(), outcome.status(),
                            outcome.content(), outcome.failure(), run.setup.model(), run.accounting.shown().input(),
                            run.accounting.shown().output(), run.accounting.shown().cost(), outcome.sources(), outcome.activity(), outcome.research(),
                            outcome.refusal(), new ChatTurnPersistence.Usage(run.setup.tenant(), run.setup.actor(),
                                    run.setup.research().enabled() ? AiUsageFlow.DEEP_RESEARCH
                                            : run.setup.builder() != null ? AiUsageFlow.AGENT_BUILDER : AiUsageFlow.CHAT,
                                    run.resolved.modelConfigurationId(), run.resolved.provenance(), run.setup.model(), run.accounting));
                    recordToolCalls(run);
                    var context = context(run.accounting);
                    if (context != null && saved.status() == ChatMessage.Status.COMPLETED)
                        persistence.recordContext(run.setup.sessionId(), run.setup.assistantMessageId(), context);
                    if (!run.deleted) streams.finish(run.setup.assistantMessageId(), saved.status(), saved.failureCode(), false,
                            saved.status() == ChatMessage.Status.COMPLETED ? context : null);
                    run.persisted = true;
                    // MEM-268: Deep research replays no summary, so only an ordinary answer may start a compaction.
                    if (compactions != null && accepting.get() && !run.deleted && !run.setup.research().enabled()
                            && saved.status() == ChatMessage.Status.COMPLETED)
                        compactions.after(run.setup.actor(), run.setup.sessionId(), run.setup.assistantMessageId(),
                                run.setup.binding().policy(), run.historyBudget);
                }
                releaseIfFinished(run);
            } catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "chat.run.terminal_persistence_pending")
                        .addKeyValue("message_id", run.setup.assistantMessageId())
                        .addKeyValue("error_type", failure.getClass().getName()).log("Chat terminal persistence pending");
            }
        } finally { run.finalizing.unlock(); }
    }

    private static @Nullable ChatContextUse context(ModelAccounting accounting) {
        return accounting.contextTokens() == null || accounting.contextLimit() == null ? null
                : new ChatContextUse(accounting.contextTokens(), accounting.contextLimit());
    }

    @Override
    public void close() {
        accepting.set(false);
        var draining = active.values().toArray(Active[]::new);
        for (var run : draining) {
            run.cancel(StopReason.INTERRUPTED);
            settleWaiting(run, ChatMessage.Status.FAILED, "CHAT_INTERRUPTED");
        }
        try {
            CompletableFuture.allOf(Arrays.stream(draining).map(run -> run.finished).toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException incomplete) {
            LOG.atWarn().addKeyValue("event", "chat.shutdown.drain_incomplete")
                    .log("Chat shutdown drain incomplete; durable lease reconciliation remains responsible");
        }
    }

    private static String failureCode(Throwable failure, ModelBinding binding) {
        // Only a typed turn failure or the adapter's reading of its own failure names the code: arbitrary provider
        // messages can contain private content.
        return TurnFailureException.reportedIn(failure).or(() -> binding.providerFailure(failure))
                .map(TurnFailure::code).orElse("CHAT_EXECUTION_FAILED");
    }

    private enum StopReason { USER, INTERRUPTED }
    private record Outcome(ChatMessage.Status status, String content, String failure, List<ChatSource> sources,
                           ChatActivity activity, ChatResearch research, @Nullable String refusal) {}

    private static final class Active {
        // Replaced once, by the sending thread, when MCP tools open after registration and before dispatch.
        volatile ChatTurnSetup setup;
        /** The history budget the turn was resolved with, which MEM-268 compaction measures the next turn against. */
        volatile int historyBudget;
        final ModelResolver.Resolved resolved;
        final ChatSettingsService.TurnPolicy policy;
        final String question;
        final List<ChatMessage> earlier;
        final @Nullable String uiLanguage;
        /** MEM-195: set before the answer model runs, when the turn is grounded or blocks phrases. */
        volatile @Nullable CitationGate gate;
        /** Whether a step came after the last text: the model's rounds are joined into one answer, a paragraph each. */
        volatile boolean stepped;
        volatile @Nullable String refusal;
        final StringBuilder content = new StringBuilder();
        final List<ChatSource> sources = new ArrayList<>();
        final ChatActivityRecorder recorder = new ChatActivityRecorder();
        final ChatResearchRecorder agents = new ChatResearchRecorder();
        final StringBuilder plan = new StringBuilder();
        volatile boolean clarification;
        final AtomicReference<StopReason> stopReason = new AtomicReference<>();
        /** Calls waiting for the person's approval, by provider call ID (MEM-253). */
        final Map<String, CompletableFuture<McpTurnTools.Decision>> approvals = new ConcurrentHashMap<>();
        /** The tool each waiting call would run, for an "always allow" answer (MEM-267). */
        final Map<String, McpTurnTools.Binding> approvalBindings = new ConcurrentHashMap<>();
        final Sinks.One<Boolean> cancellation = Sinks.one();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        CompletableFuture<Void> draining = CompletableFuture.completedFuture(null);
        volatile boolean drained;
        volatile boolean persisted;
        volatile boolean deleted;
        volatile long renewedAt = System.nanoTime();
        /** Whose bound the run counts against: the person who sent the command. */
        final ActorId claimant;
        /** Whether the run holds a slot; written under {@code turns}, when admitted or when promoted from the queue. */
        volatile boolean slot;
        /** Whether the run holds a place in the queue, from admission until it is promoted or ends; under {@code turns}. */
        volatile boolean place;
        volatile long waitingSince;
        // Serializes persistence retries without holding the state monitor used by Stop/text callbacks.
        final ReentrantLock finalizing = new ReentrantLock();
        volatile Outcome outcome;
        volatile ModelAccounting accounting = ModelAccounting.NONE;
        /** For the turn metrics: when the turn was admitted, whether it was grounded then, and whether text has shown. */
        final long admitted = System.nanoTime();
        final boolean grounded;
        final AtomicBoolean firstTextShown = new AtomicBoolean();
        Active(ChatTurnSetup setup, ModelResolver.Resolved resolved, ChatSettingsService.TurnPolicy policy, String question,
               List<ChatMessage> earlier, @Nullable String uiLanguage, ActorId claimant, boolean slot) {
            this.setup = setup; this.resolved = resolved; this.policy = policy; this.question = question;
            this.earlier = List.copyOf(earlier); this.uiLanguage = uiLanguage; this.claimant = claimant; this.slot = slot; this.place = !slot;
            this.grounded = setup.options().grounded();
        }
        synchronized int sourceCount() { return sources.size(); }
        /** Replaces the answer with a refusal; returns whether answer text had already been released. */
        synchronized boolean refuse(String reason, String text) {
            check();
            boolean shown = !content.isEmpty();
            content.setLength(0);
            content.append(text);
            refusal = reason;
            return shown;
        }
        synchronized void cancel(StopReason reason) {
            if (outcome != null) return;
            stopReason.compareAndSet(null, reason);
            cancellation.tryEmitValue(true);
            // A call waiting for the person is declined, so Stop does not wait out the approval period.
            approvals.values().forEach(pending -> pending.complete(McpTurnTools.Decision.DECLINED));
        }
        synchronized String paragraph(String text) {
            if (!stepped) return text;
            stepped = false;
            boolean spaced = content.isEmpty() || Character.isWhitespace(content.charAt(content.length() - 1))
                    || Character.isWhitespace(text.charAt(0));
            return spaced ? text : "\n\n" + text;
        }
        synchronized void append(String text, int limit) {
            check();
            if (content.length() + text.length() > limit) throw TurnFailure.OUTPUT_LIMIT.exception();
            content.append(text);
        }
        synchronized void activity(ChatActivityEvent event) {
            check();
            // A research agent numbers its own evidence; only sources merged into the turn join this sequence.
            if (event instanceof ChatToolEvent tool && tool.source() != null && tool.parentToolCallId() == null) {
                if (tool.source().citationId() != sources.size() + 1)
                    throw new IllegalStateException("Invalid Chat evidence sequence");
                sources.add(tool.source());
            }
            // The plan is stored with the outcome; beyond its column bound the rest is dropped, never failing the turn.
            if (event instanceof ChatResearchEvent research && research.kind() == ChatResearchEvent.Kind.CLARIFICATION) clarification = true;
            if (event instanceof ChatResearchEvent research && research.kind() == ChatResearchEvent.Kind.PLAN_DELTA
                    && research.text() instanceof String text && plan.length() < ChatResearch.MAX_PLAN)
                plan.append(text, 0, Math.min(text.length(), ChatResearch.MAX_PLAN - plan.length()));
            recorder.accept(event, content.length());
            agents.accept(event);
        }
        synchronized void finish(ChatMessage.Status status, String failure) {
            if (outcome == null) {
                // render_gui was removed: no turn creates read-only UI artifacts; stored ones still render from history.
                var activity = recorder.seal();
                var research = new ChatResearch(clarification, plan.isEmpty() ? null : plan.toString(), agents.seal());
                if (stopReason.get() == StopReason.USER) outcome = new Outcome(ChatMessage.Status.CANCELED, content.toString(), null, List.copyOf(sources), activity, research, null);
                else if (stopReason.get() == StopReason.INTERRUPTED) outcome = new Outcome(ChatMessage.Status.FAILED,
                        content.toString(), "CHAT_INTERRUPTED", List.copyOf(sources), activity, research, null);
                else outcome = new Outcome(status, content.toString(), failure, List.copyOf(sources), activity, research,
                        status == ChatMessage.Status.COMPLETED ? refusal : null);
            }
        }
        void check() {
            if (stopReason.get() != null || outcome != null) throw new CancellationException("Chat stopped");
        }
    }
}
