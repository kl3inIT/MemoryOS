package io.memoryos.chat.application;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatFileService;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorized turn transactions; SQL remains in the concrete Chat repository.
 */
@Service
public class ChatTurnPersistence {
    private final TenantAccessResolver tenants;
    private final JdbcChatRepository chats;
    private final PersonaProperties persona;
    private final ChatFileService files;

    public ChatTurnPersistence(TenantAccessResolver tenants, JdbcChatRepository chats, PersonaProperties persona, ChatFileService files) {
        this.tenants = tenants;
        this.chats = chats;
        this.persona = persona;
        this.files = files;
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
                               String text, Duration timeout, int contextTokenLimit, @Nullable ModelSelection selection) {
        return reserve(actor, sessionId, new ChatCommand(ChatCommand.Operation.SEND, parentId, requestId, text,
                selection == null ? null : selection.requestedId()), timeout, contextTokenLimit, selection);
    }

    @Transactional
    public Reservation reserve(ActorId actor, UUID sessionId, ChatCommand command,
                               Duration timeout, int contextTokenLimit, @Nullable ModelSelection selection) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw ChatException.invalid("Invalid chat message or deadline.");
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
        // Initialization is insert-only: editor-owned settings must survive every send.
        chats.provisionPersona(tenant, persona.getName(), persona.getInstructions(), persona.getModel());
        var settings = chats.persona(sessionId, true);
        if (selection != null && selection.contextRevision() != null && !selection.contextRevision().equals(settings.revision()))
            throw ChatException.conflict();
        int effectiveContext = settings.options().contextTokenLimit() == null ? contextTokenLimit
                : Math.min(contextTokenLimit, settings.options().contextTokenLimit());
        String instructions = settings.instructions();
        if (selection == null) ChatTurnSetup.validateQuestion(instructions, text, effectiveContext);
        else {
            var binding = selection.binding().forOptions(settings.options());
            instructions = io.memoryos.chat.prompts.ChatPrompts.resolve(instructions, binding.toolCalling() && settings.options().searchEnabled(), Instant.now());
            ChatTurnSetup.validateQuestion(instructions, text, effectiveContext, binding, selection.promptContribution());
        }
        UUID user = command.operation() == ChatCommand.Operation.REGENERATE ? target.id() : UUID.randomUUID();
        var attachments = command.operation() == ChatCommand.Operation.REGENERATE ? target.files()
                : files.admit(tenant, actor, command.fileIds());
        UUID assistant = UUID.randomUUID();
        if (command.operation() == ChatCommand.Operation.REGENERATE) chats.insertAssistant(sessionId, user, assistant, timeout);
        else chats.insertPair(sessionId, parentId, command.requestId(), user, assistant, text, timeout, attachments);
        if (selection != null) chats.saveModelSelection(sessionId,
                command.operation() == ChatCommand.Operation.REGENERATE ? assistant : user,
                assistant, selection.requestedId(), selection.selectedId(), selection.fallbackReason());
        chats.saveCommand(sessionId, command, user, assistant, selection == null ? null : selection.selectedId(),
                selection == null ? null : selection.fallbackReason());
        var context = context(actor, tenant, sessionId, user, assistant, settings, instructions);
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
                || !Objects.equals(previous.requestedModelId(), command.modelConfigurationId()))
            throw ChatException.conflict();
    }

    @Transactional
    public TurnContext loadContext(ActorId actor, UUID sessionId, Reservation reservation) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
        if (reservation.context() != null) return reservation.context();
        var persona = chats.persona(sessionId, false);
        return context(actor, tenant, sessionId, reservation.userMessageId(), reservation.assistantMessageId(), persona, persona.instructions());
    }

    private TurnContext context(ActorId actor, TenantId tenant, UUID session, UUID user, UUID assistant, JdbcChatRepository.Persona settings, String instructions) {
        var history = chats.context(session, user, 200);
        var workspaceFiles = files.admit(tenant, actor, settings.fileIds());
        var plaintext = new LinkedHashMap<UUID, ChatFileService.FileText>();
        java.util.stream.Stream.concat(history.stream().flatMap(message -> message.files().stream()), workspaceFiles.stream())
                .map(io.memoryos.chat.ChatFileDescriptor::id)
                .distinct().limit(20).forEach(id -> {
                    try { plaintext.put(id, files.read(actor, tenant, id, 0, 16000)); }
                    catch (ChatException unavailable) { /* Old descriptors survive deletion, not authority. */ }
                });
        return new TurnContext(actor, tenant, settings.model(), instructions, history,
                chats.control(assistant).deadline(), settings.options(), plaintext, workspaceFiles);
    }

    public record TurnContext(ActorId actor, TenantId tenant, String model, String instructions,
                              List<ChatMessage> newestFirst, Instant deadline, ChatTurnOptions options,
                              Map<UUID, ChatFileService.FileText> fileTexts, List<io.memoryos.chat.ChatFileDescriptor> workspaceFiles) {
        public TurnContext { newestFirst = List.copyOf(newestFirst); fileTexts = Map.copyOf(fileTexts); workspaceFiles = List.copyOf(workspaceFiles); }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst, Instant deadline, ChatTurnOptions options) {
            this(actor, tenant, model, instructions, newestFirst, deadline, options, Map.of(), List.of());
        }
        public TurnContext(ActorId actor, TenantId tenant, String model, String instructions, List<ChatMessage> newestFirst, Instant deadline) {
            this(actor, tenant, model, instructions, newestFirst, deadline, ChatTurnOptions.DEFAULT);
        }
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

    public record TerminalOutcome(ChatMessage.Status status, @Nullable String failureCode) {}

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources) {
        if (status == null || status == ChatMessage.Status.RUNNING || partial == null || partial.length() > 1000000 || sources.size() > 24)
            throw ChatException.invalid("Invalid terminal outcome.");
        chats.finish(session, assistant, status, partial, failure, model, input, output, cost, sources);
        var saved = chats.control(assistant);
        return new TerminalOutcome(saved.status(), saved.failureCode());
    }

    @Transactional
    public int expireRuns() {
        return chats.expireRuns();
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
