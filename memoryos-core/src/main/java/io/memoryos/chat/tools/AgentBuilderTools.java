package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.ai.ReasoningEffort;
import io.memoryos.chat.AssistantPatch;
import io.memoryos.chat.ChatPersonaService.BuilderChoices;
import io.memoryos.chat.ChatPersonaService.BuilderOption;
import io.memoryos.chat.ChatPersonaService.PersonaInput;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * MEM-280: the Agent Builder's tools, after LobeHub's {@code updateConfig} and {@code updatePrompt}. Each proposal is
 * merged into the form the turn was sent with and checked by {@code check}, the rules of the editor's Save; an
 * accepted one goes to the browser as an {@link AssistantPatch} and becomes the form the next call builds on. Nothing
 * is written to the assistant: the person reviews the change and saves it.
 */
public final class AgentBuilderTools {
    /** Options listed in the builder's context per kind; {@code find_options} reaches the rest. */
    public static final int LISTED_OPTIONS = 50;
    private static final int FOUND_OPTIONS = 20;

    /**
     * One builder turn: the form as the browser sent it, what its editor may choose, Save's check of a merged form
     * (throws a {@link BusinessException} with the reason) and where accepted changes go, with their tool call ID.
     */
    public record Turn(PersonaInput draft, BuilderChoices choices, Consumer<PersonaInput> check,
                       BiConsumer<String, AssistantPatch> changes) {}

    private final Turn turn;
    private final Runnable checkActive;
    private final ChatToolActivity activity;
    private PersonaInput form;

    public AgentBuilderTools(Turn turn, Runnable checkActive, ChatToolActivity activity) {
        this.turn = turn;
        this.checkActive = checkActive;
        this.activity = activity;
        this.form = turn.draft();
    }

    @LlmTool(name = "update_assistant", description = "Change the assistant's settings in the user's unsaved form. "
            + "Pass only the fields to change, each with its whole new value; batch related changes in one call. "
            + "Lists replace the current list: to add one item, pass the current items plus the new one. The user "
            + "reviews the change and saves it; nothing is saved by this call.")
    public String updateAssistant(
            @LlmTool.Param(description = "The assistant's name, at most 200 characters", required = false) @Nullable String name,
            @LlmTool.Param(description = "The role it plays, at most 80 characters; empty to clear", required = false) @Nullable String title,
            @LlmTool.Param(description = "One or two sentences on what it helps with and for whom, shown on the assistant's card, at most 2000 characters; it does not change how it answers", required = false) @Nullable String description,
            @LlmTool.Param(description = "First message of a new conversation, shown once at the start, at most 500 characters; empty to clear; it does not change how it answers", required = false) @Nullable String greeting,
            @LlmTool.Param(description = "At most 4 suggested first questions, each at most 1000 characters", required = false) @Nullable List<String> starterPrompts,
            @LlmTool.Param(description = "Enabled tools, any of: search (internal documents), web_search, image_generation, code_interpreter", required = false) @Nullable List<String> tools,
            @LlmTool.Param(description = "IDs of the Sources to search, from the options; empty for every document the reader may open", required = false) @Nullable List<String> sourceIds,
            @LlmTool.Param(description = "IDs of the Document Sets to search, from the options", required = false) @Nullable List<String> documentSetIds,
            @LlmTool.Param(description = "IDs of the MCP servers to attach, from the options", required = false) @Nullable List<String> mcpServerIds,
            @LlmTool.Param(description = "ID of the model, from the options", required = false) @Nullable String modelConfigurationId,
            @LlmTool.Param(description = "Reasoning level: OFF, LOW, MEDIUM or HIGH, only for a model that reasons; empty to leave it to the model", required = false) @Nullable String reasoningEffort,
            @LlmTool.Param(description = "Answer only from documents, citing them", required = false) @Nullable Boolean grounded) {
        checkActive.run();
        AssistantPatch patch;
        try {
            patch = new AssistantPatch(trimmed(name), trimmed(title), description, null, null, greeting,
                    starterPrompts, tools == null ? null : new LinkedHashSet<>(tools), ids(sourceIds),
                    ids(documentSetIds), ids(mcpServerIds), id(modelConfigurationId), effort(reasoningEffort), grounded);
        } catch (IllegalArgumentException invalid) {
            activity.fail();
            return "Not changed: " + invalid.getMessage();
        }
        return apply(patch);
    }

    @LlmTool(name = "write_instructions", description = "Replace the assistant's instructions in the user's unsaved form "
            + "with the whole new text. Instructions decide how the assistant behaves and answers: scope, use of "
            + "documents, tone, answer length and format. To change part of them, send the whole text with only that "
            + "part changed; keep the rest, its structure and language as they are. Nothing is saved by this call.")
    public String writeInstructions(
            @LlmTool.Param(description = "The complete instructions, at most 32000 characters") String instructions) {
        checkActive.run();
        if (instructions == null) {
            activity.fail();
            return "Not changed: give the complete instructions.";
        }
        return apply(AssistantPatch.instructions(instructions));
    }

    @LlmTool(name = "find_options", description = "Find Sources, Document Sets, MCP servers or models the user may attach "
            + "whose name contains the query, beyond those listed in the context.")
    public String findOptions(
            @LlmTool.Param(description = "One of: sources, document_sets, mcp_servers, models") String kind,
            @LlmTool.Param(description = "Part of the name, any case") String query) {
        checkActive.run();
        var needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<BuilderOption> options = switch (kind == null ? "" : kind) {
            case "sources" -> turn.choices().sources();
            case "document_sets" -> turn.choices().documentSets();
            case "mcp_servers" -> turn.choices().mcpServers();
            case "models" -> turn.choices().models().stream().map(model -> new BuilderOption(model.id(), model.name())).toList();
            default -> null;
        };
        if (options == null) {
            activity.fail();
            return "Unknown kind. Use sources, document_sets, mcp_servers or models.";
        }
        var found = options.stream().filter(option -> option.name().toLowerCase(Locale.ROOT).contains(needle))
                .limit(FOUND_OPTIONS).map(option -> option.id() + " " + option.name()).toList();
        return found.isEmpty() ? "No option matches." : String.join("\n", found);
    }

    private synchronized String apply(AssistantPatch patch) {
        if (patch.empty()) {
            activity.fail();
            return "Nothing to change: pass at least one field.";
        }
        var merged = merged(form, patch);
        try {
            turn.check().accept(merged);
        } catch (BusinessException refused) {
            activity.fail();
            return "Not changed: " + refused.getMessage() + " Nothing of this call was applied.";
        }
        checkActive.run();
        form = merged;
        var call = activity.current();
        turn.changes().accept(call == null ? "" : call.id(), patch);
        return "Changed in the unsaved form: " + changedFields(patch)
                + ". The user sees it as a before → after card; tell them in one or two sentences what you changed and "
                + "why, and that Lưu saves it.";
    }

    /** The form with the patch applied, as the browser will hold it. */
    static PersonaInput merged(PersonaInput form, AssistantPatch patch) {
        return new PersonaInput(
                patch.name() != null ? patch.name() : form.name(),
                patch.title() != null ? blankToNull(patch.title()) : form.title(),
                patch.description() != null ? patch.description() : form.description(),
                patch.instructions() != null ? patch.instructions() : form.instructions(),
                patch.greeting() != null ? blankToNull(patch.greeting()) : form.greeting(),
                patch.starterPrompts() != null ? patch.starterPrompts() : form.starterPrompts(),
                patch.sourceIds() != null ? patch.sourceIds() : form.sourceIds(),
                patch.documentSetIds() != null ? patch.documentSetIds() : form.documentSetIds(),
                patch.tools() != null ? patch.tools() : form.tools(),
                patch.mcpServerIds() != null ? patch.mcpServerIds() : form.mcpServerIds(),
                patch.modelConfigurationId() != null ? patch.modelConfigurationId() : form.modelConfigurationId(),
                patch.reasoningEffort() != null
                        ? (patch.reasoningEffort().isEmpty() ? null : ReasoningEffort.valueOf(patch.reasoningEffort()))
                        : form.reasoningEffort(),
                form.fileIds(), form.avatarSeed(), form.avatarFileId(), form.removeAvatar(), form.labelIds(),
                patch.grounded() != null ? patch.grounded() : form.grounded());
    }

    private static String changedFields(AssistantPatch patch) {
        var fields = new ArrayList<String>();
        if (patch.name() != null) fields.add("name");
        if (patch.title() != null) fields.add("title");
        if (patch.description() != null) fields.add("description");
        if (patch.instructions() != null) fields.add("instructions");
        if (patch.greeting() != null) fields.add("greeting");
        if (patch.starterPrompts() != null) fields.add("starter prompts");
        if (patch.tools() != null) fields.add("tools");
        if (patch.sourceIds() != null) fields.add("sources");
        if (patch.documentSetIds() != null) fields.add("document sets");
        if (patch.mcpServerIds() != null) fields.add("MCP servers");
        if (patch.modelConfigurationId() != null) fields.add("model");
        if (patch.reasoningEffort() != null) fields.add("reasoning level");
        if (patch.grounded() != null) fields.add("answer only from documents");
        return String.join(", ", fields);
    }

    private static @Nullable String trimmed(@Nullable String value) {
        return value == null ? null : value.strip();
    }

    private static @Nullable String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }

    private static @Nullable String effort(@Nullable String value) {
        if (value == null) return null;
        var level = value.strip().toUpperCase(Locale.ROOT);
        if (level.isEmpty()) return "";
        try {
            return ReasoningEffort.valueOf(level).name();
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("the reasoning level must be OFF, LOW, MEDIUM or HIGH.");
        }
    }

    private static @Nullable UUID id(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("'" + value + "' is not an option ID; use find_options.");
        }
    }

    private static @Nullable List<UUID> ids(@Nullable List<String> values) {
        if (values == null) return null;
        return values.stream().filter(Objects::nonNull).map(AgentBuilderTools::id).filter(Objects::nonNull)
                .distinct().collect(Collectors.toList());
    }
}
