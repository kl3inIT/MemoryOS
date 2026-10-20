package io.memoryos.chat.prompts;

import io.memoryos.chat.ChatPersonaService.BuilderChoices;
import io.memoryos.chat.ChatPersonaService.BuilderOption;
import io.memoryos.chat.ChatPersonaService.PersonaInput;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * MEM-280: the Agent Builder's system prompt, after LobeHub's Agent Builder role ({@code builtin-agents/agent-builder/
 * systemRole.ts}): it configures the assistant and never becomes it, reads the form instead of asking, settles
 * identity, then model and tools, then the instructions, and says before → after for each change. MemoryOS adds what
 * its editor needs: changes wait in the unsaved form for Lưu, and the list of changes it asks about first.
 */
public final class AgentBuilderPrompt {
    private AgentBuilderPrompt() {}

    static final String ROLE = """
            You are the Agent Builder of MemoryOS, a company knowledge assistant. You help the user set up one \
            assistant (an AI agent their colleagues chat with) through conversation, by changing its settings with \
            your tools.

            The current date is {{CURRENT_DATETIME}}.

            <role>
            You configure the assistant; you never become it. When a message could be read either as a question for \
            the assistant or as a description of the assistant to build, read it as the latter: "Nghỉ phép năm bao \
            nhiêu ngày?" means the assistant should answer leave questions, perhaps as a suggested first question; \
            it is not a question for you to answer. To try the assistant, the user opens the "Chat thử" tab beside you.
            </role>

            <tools>
            - update_assistant: change name, title, description, greeting, suggested first questions, tools, \
            sources, document sets, MCP servers, model, reasoning level and "answer only from documents", several \
            fields in one call.
            - write_instructions: replace the whole instructions, which decide how the assistant behaves and \
            answers: its scope, tone, answer length and format.
            - find_options: find sources, document sets, MCP servers or models beyond those listed below.
            The assistant's current settings and the options the user may choose are in <current_assistant> and \
            <options>. Read them there; never ask the user for them and never invent an ID.
            </tools>

            <workflow>
            1. Understand what the assistant should do and for whom.
            2. Settle identity first (name, title, description), then tools, sources and model, then the greeting and \
            suggested questions, and write the instructions last so they can refer to the tools that are on.
            When the assistant is new or its instructions are empty, finish with write_instructions in the same turn.
            3. After each change, say what changed as before → after, in a few lines.
            </workflow>

            <rules>
            - Your changes go to the user's unsaved form. They take effect only when the user presses "Lưu". Never \
            say a change is saved, published or live.
            - Ask first, in one short question, before you: switch the model; turn off document search, web search or \
            an MCP server; remove a source or document set; turn off "answer only from documents". Change the rest \
            directly; the user can undo any change.
            - Choose only from <options>. You cannot share the assistant, publish it to Khám phá, label it, connect \
            it to chat channels, transfer or delete it, or attach files; if asked, say where the user does it (the \
            Chia sẻ and Kênh tabs, or the Tài liệu section for files).
            - A request about how the assistant answers (shorter, more formal, in a table, with citations) changes \
            the instructions; the description and greeting do not steer its answers.
            - Write instructions as clear rules for the assistant: its role and scope, how it uses the company's \
            documents (cite them; say plainly when nothing is found), tone, and answer format. Write them in the \
            language the assistant's users will write in, Vietnamese unless the user says otherwise.
            - At most four suggested questions, each a real question a colleague would ask.
            - When a change is refused, explain the reason in plain words and offer what is possible instead.
            - Reply in the user's language, briefly; use the field names the user sees, not raw keys or IDs.
            </rules>
            """;

    /** The builder's system message for the form as the browser sent it and what its editor may choose. */
    public static String system(PersonaInput form, BuilderChoices choices, int listed) {
        Map<UUID, String> models = choices.models().stream()
                .collect(Collectors.toMap(model -> model.id(), model -> model.name(), (first, second) -> first));
        Map<UUID, String> sources = names(choices.sources());
        Map<UUID, String> sets = names(choices.documentSets());
        Map<UUID, String> servers = names(choices.mcpServers());
        var current = new StringBuilder("<current_assistant>\n");
        line(current, "name", form.name());
        line(current, "title", form.title());
        line(current, "description", form.description());
        line(current, "greeting", form.greeting());
        line(current, "suggested questions", String.join(" | ", form.starterPrompts()));
        line(current, "tools", form.tools() == null ? "" : String.join(", ", form.tools().stream().sorted().toList()));
        line(current, "sources", refs(form.sourceIds(), sources));
        line(current, "document sets", refs(form.documentSetIds(), sets));
        line(current, "MCP servers", refs(form.mcpServerIds(), servers));
        line(current, "model", form.modelConfigurationId() == null ? "the default of the model list"
                : ref(form.modelConfigurationId(), models));
        line(current, "reasoning level", form.reasoningEffort() == null ? "left to the model" : form.reasoningEffort().name());
        line(current, "answer only from documents", String.valueOf(Boolean.TRUE.equals(form.grounded())));
        current.append("instructions:\n").append(form.instructions().isBlank() ? "(empty)" : form.instructions())
                .append("\n</current_assistant>\n");
        var options = new StringBuilder("<options>\nmodels:\n");
        choices.models().forEach(model -> options.append("- ").append(model.id()).append(' ').append(model.name())
                .append(model.isDefault() ? " (default)" : "").append(model.reasoning() ? " (reasons)" : "").append('\n'));
        listing(options, "sources", choices.sources(), listed);
        listing(options, "document sets", choices.documentSets(), listed);
        listing(options, "MCP servers", choices.mcpServers(), listed);
        options.append("tools: search (internal documents), web_search, image_generation, code_interpreter\n</options>");
        return ROLE + "\n" + current + options;
    }

    private static Map<UUID, String> names(List<BuilderOption> options) {
        return options.stream().collect(Collectors.toMap(BuilderOption::id, BuilderOption::name, (first, second) -> first));
    }

    private static void line(StringBuilder out, String field, @Nullable String value) {
        out.append(field).append(": ").append(value == null || value.isBlank() ? "(empty)" : value).append('\n');
    }

    private static String refs(@Nullable List<UUID> ids, Map<UUID, String> names) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream().map(id -> ref(id, names)).collect(Collectors.joining(", "));
    }

    private static String ref(UUID id, Map<UUID, String> names) {
        return id + " " + names.getOrDefault(id, "(not among the options)");
    }

    private static void listing(StringBuilder out, String kind, List<BuilderOption> options, int listed) {
        out.append(kind).append(":\n");
        options.stream().limit(listed).forEach(option -> out.append("- ").append(option.id()).append(' ')
                .append(option.name()).append('\n'));
        if (options.size() > listed)
            out.append("(").append(options.size() - listed).append(" more; use find_options)\n");
        if (options.isEmpty()) out.append("(none)\n");
    }
}
