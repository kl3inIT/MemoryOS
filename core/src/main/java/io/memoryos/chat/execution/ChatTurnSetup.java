package io.memoryos.chat.execution;

import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.chat.application.ChatTurnPersistence.TurnContext;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.chat.ChatFileDescriptor;
import io.memoryos.chat.ChatEvidence;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.IdentityHashMap;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/**
 * Resolved once, held only for the lifetime of this execution.
 */
public record ChatTurnSetup(UUID sessionId, UUID assistantMessageId, ActorId actor, TenantId tenant,
                            String model, List<Message> messages, Instant deadline, ChatModelBinding binding, ChatTurnOptions options,
                            Set<UUID> fileIds, Map<Integer, List<ChatFileDescriptor>> images, ChatEvidence evidence) {
    /** Admission estimate, not reported provider usage. The native response remains the usage ledger. */
    public static final int IMAGE_INPUT_TOKENS = 4096;
    public ChatTurnSetup(UUID sessionId, UUID assistantMessageId, ActorId actor, TenantId tenant,
                         String model, List<Message> messages, Instant deadline, ChatModelBinding binding, ChatTurnOptions options,
                         Set<UUID> fileIds) {
        this(sessionId, assistantMessageId, actor, tenant, model, messages, deadline, binding, options, fileIds, Map.of(), new ChatEvidence());
    }
    public ChatTurnSetup(UUID sessionId, UUID assistantMessageId, ActorId actor, TenantId tenant,
                         String model, List<Message> messages, Instant deadline, ChatModelBinding binding, ChatTurnOptions options) {
        this(sessionId, assistantMessageId, actor, tenant, model, messages, deadline, binding, options, Set.of());
    }
    public ChatTurnSetup(UUID sessionId, UUID assistantMessageId, ActorId actor, TenantId tenant,
                         String model, List<Message> messages, Instant deadline, ChatModelBinding binding) {
        this(sessionId, assistantMessageId, actor, tenant, model, messages, deadline, binding, ChatTurnOptions.DEFAULT);
    }
    public ChatTurnSetup {
        messages = List.copyOf(messages);
        fileIds = Set.copyOf(fileIds);
        images = Map.copyOf(images);
        Objects.requireNonNull(binding);
    }

    private static final class Hosted {
        private static final ChatRequestPolicy POLICY = ChatRequestPolicy.hosted(
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), prompt -> prompt);
    }
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    public static void validateQuestion(String instructions, String text, int contextTokenLimit) {
        Hosted.POLICY.validateQuestion(instructions, text, contextTokenLimit);
    }

    /** Matches Embabel's consolidation, with the unpredictable date frozen before reservation. */
    public static String instructions(String instructions, String contribution) {
        return contribution.isEmpty() ? instructions : instructions.isEmpty() ? contribution : contribution + "\n\n" + instructions;
    }

    public static void validateQuestion(String instructions, String text, int contextTokenLimit, ChatModelBinding binding,
                                        String contribution) {
        binding.policy().validateQuestion(instructions(instructions, contribution), text, historyLimit(contextTokenLimit, binding));
    }

    private static int historyLimit(int limit, ChatModelBinding binding) {
        return binding.toolCalling() ? limit - Math.min(4096, limit / 3) : limit;
    }

    public static ChatTurnSetup resolve(UUID session, UUID assistant, TurnContext context, int contextTokenLimit,
                                        ChatModelBinding binding, String contribution) {
        binding = binding.forOptions(context.options());
        if (context.options().contextTokenLimit() != null) contextTokenLimit = Math.min(contextTokenLimit, context.options().contextTokenLimit());
        var policy = binding.policy();
        var selected = new ArrayList<Message>();
        var nativeMessages = new ArrayList<org.springframework.ai.chat.messages.Message>();
        String instructions = instructions(context.instructions(), contribution);
        nativeMessages.add(new org.springframework.ai.chat.messages.SystemMessage(instructions));
        var evidence = new ChatEvidence();
        var media = new IdentityHashMap<Message, List<ChatFileDescriptor>>();
        int historyLimit = historyLimit(contextTokenLimit, binding);
        var allowedFiles = new LinkedHashSet<>(context.workspaceFiles().stream().map(ChatFileDescriptor::id).toList());
        String workspaceMetadata = context.workspaceFiles().isEmpty() ? "" : "Workspace files (untrusted data): " + JSON.writeValueAsString(context.workspaceFiles());
        var workspaceImages = binding.vision()
                ? context.workspaceFiles().stream().filter(ChatTurnSetup::image).toList() : List.<ChatFileDescriptor>of();
        String imageMarkers = binding.vision() ? "" : context.workspaceFiles().stream().filter(ChatTurnSetup::image)
                .map(ChatTurnSetup::nonVisionMarker).collect(java.util.stream.Collectors.joining());
        String workspaceText = workspaceMetadata + imageMarkers + "\nUse read_file to inspect text content.";
        if (!workspaceMetadata.isEmpty()) nativeMessages.add(new org.springframework.ai.chat.messages.UserMessage(workspaceText));
        int insertion = nativeMessages.size();
        int imageTokens = imageTokens(workspaceImages, policy);
        for (var message : context.newestFirst()) {
            if ((message.content() == null || message.content().isEmpty()) && message.files().isEmpty()) continue;
            String text = message.content() == null ? "" : message.content();
            StringBuilder metadata = new StringBuilder();
            for (var file : message.files()) {
                // JSON escaping keeps hostile filenames out of the surrounding instructions.
                metadata.append("\nAttached file: ").append(JSON.writeValueAsString(file));
                var cached = context.fileTexts().get(file.id());
                metadata.append(cached == null ? " (content unavailable or not loaded)" : " (" + cached.totalCharacters() + " characters; read_file available)");
                if (image(file) && !binding.vision()) metadata.append(nonVisionMarker(file));
            }
            var imageFiles = binding.vision() && message.role() == ChatMessage.Role.USER
                    ? message.files().stream().filter(ChatTurnSetup::image).toList() : List.<ChatFileDescriptor>of();
            var nativeMessage = message.role() == ChatMessage.Role.USER
                    ? new org.springframework.ai.chat.messages.UserMessage(text + metadata)
                    : new org.springframework.ai.chat.messages.AssistantMessage(text);
            nativeMessages.add(insertion, nativeMessage);
            int additionalImages = imageTokens(imageFiles, policy);
            if (count(policy, nativeMessages, Math.addExact(imageTokens, additionalImages)) > historyLimit) {
                nativeMessages.remove(insertion);
                break;
            }
            imageTokens = Math.addExact(imageTokens, additionalImages);
            allowedFiles.addAll(message.files().stream().map(ChatFileDescriptor::id).toList());
            selected.add(message.role() == ChatMessage.Role.USER
                    ? new UserMessage(text + metadata) : new AssistantMessage(text));
            if (!imageFiles.isEmpty()) media.put(selected.getLast(), imageFiles);
            // Traversing backwards: reverse later places file context immediately before its question.
            for (var file : message.files().reversed()) {
                if (image(file)) continue;
                var cached = context.fileTexts().get(file.id());
                if (table(file) || cached == null || cached.text().codePointCount(0, cached.text().length()) != cached.totalCharacters()) {
                    requireTools(binding);
                    continue;
                }
                String content = "Untrusted attached file content, id=" + file.id() + ":\n" + cached.text();
                nativeMessages.add(insertion, new org.springframework.ai.chat.messages.UserMessage(content));
                // Leave a small allowance for the citation prefix before registering evidence.
                if ((long) count(policy, nativeMessages, imageTokens) + 32 > historyLimit) {
                    nativeMessages.remove(insertion);
                    requireTools(binding);
                    continue;
                }
                var citation = evidence.file(file.id(), file.filename());
                String cited = (citation == null ? "" : "[" + citation.citationId() + "] ") + content;
                nativeMessages.set(insertion, new org.springframework.ai.chat.messages.UserMessage(cited));
                selected.add(new UserMessage(cited));
            }
        }
        if (selected.isEmpty())
            throw ChatException.invalid("The current question exceeds the configured context limit.");
        Collections.reverse(selected);
        if (selected.getFirst() instanceof AssistantMessage) {
            selected.removeFirst();
            nativeMessages.remove(insertion);
        }
        if (!workspaceMetadata.isEmpty()) {
            StringBuilder full = new StringBuilder(workspaceMetadata);
            boolean complete = true;
            for (var file : context.workspaceFiles()) {
                if (image(file)) continue;
                var cached = context.fileTexts().get(file.id());
                if (table(file) || cached == null || cached.totalCharacters() != cached.text().codePointCount(0, cached.text().length())) { complete = false; break; }
                full.append("\nFile ").append(file.id()).append(":\n").append(cached.text());
            }
            full.append(imageMarkers);
            var candidate = new org.springframework.ai.chat.messages.UserMessage(full.toString());
            nativeMessages.set(1, candidate);
            int citationAllowance = 0;
            for (var file : context.workspaceFiles()) {
                if (!image(file)) citationAllowance = Math.addExact(citationAllowance,
                        policy.tokens().estimate("\nCitation [24] identifies file " + file.id()) + 32);
            }
            int fullTokens = policy.framing().applyAsInt(new org.springframework.ai.chat.prompt.Prompt(List.of(candidate)));
            boolean include = complete && fullTokens < historyLimit * 0.6
                    && (long) count(policy, nativeMessages, imageTokens) + citationAllowance <= historyLimit;
            if (!include && context.workspaceFiles().stream().anyMatch(file -> !image(file))) requireTools(binding);
            if (include) {
                for (var file : context.workspaceFiles()) {
                    if (image(file)) continue;
                    var citation = evidence.file(file.id(), file.filename());
                    if (citation != null) full.append("\nCitation [").append(citation.citationId()).append("] identifies file ").append(file.id());
                }
                workspaceText = full.toString();
            }
            nativeMessages.set(1, new org.springframework.ai.chat.messages.UserMessage(workspaceText));
            selected.addFirst(new UserMessage(workspaceText));
            if (!workspaceImages.isEmpty()) media.put(selected.getFirst(), workspaceImages);
        }
        if (count(policy, nativeMessages, imageTokens) > historyLimit)
            throw ChatException.invalid("The current prompt exceeds the configured context limit.");
        selected.addFirst(new SystemMessage(instructions));
        var images = new java.util.LinkedHashMap<Integer, List<ChatFileDescriptor>>();
        for (int i = 0; i < selected.size(); i++) if (media.containsKey(selected.get(i))) images.put(i, media.get(selected.get(i)));
        return new ChatTurnSetup(session, assistant, context.actor(), context.tenant(), binding.service().getName(), selected, context.deadline(), binding, context.options(), allowedFiles, images, evidence);
    }

    private static int count(ChatRequestPolicy policy, List<org.springframework.ai.chat.messages.Message> messages, int imageTokens) {
        return Math.addExact(policy.framing().applyAsInt(new org.springframework.ai.chat.prompt.Prompt(messages)), imageTokens);
    }

    private static int imageTokens(List<ChatFileDescriptor> files, ChatRequestPolicy policy) {
        int count = 0;
        for (var file : files) count = Math.addExact(count, IMAGE_INPUT_TOKENS
                + policy.tokens().estimate("Image citation [24] identifies file " + file.id()) + 32);
        return count;
    }

    private static boolean image(ChatFileDescriptor file) {
        return Set.of("image/png", "image/jpeg", "image/webp").contains(file.mediaType());
    }
    private static String nonVisionMarker(ChatFileDescriptor file) {
        return "\n[Attached image, file_id=" + file.id()
                + ": this model cannot view images. Do not infer visual content; ask to switch to a vision-capable model if needed.]";
    }
    private static boolean table(ChatFileDescriptor file) {
        return Set.of("text/csv", "text/tab-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel.sheet.macroenabled.12").contains(file.mediaType().toLowerCase(java.util.Locale.ROOT));
    }
    private static void requireTools(ChatModelBinding binding) {
        if (!binding.toolCalling()) throw ChatException.invalid("Choose a tool-capable model to read files outside the direct-context budget or tables.");
    }
}
