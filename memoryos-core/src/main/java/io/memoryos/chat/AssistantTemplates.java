package io.memoryos.chat;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * MemoryOS's assistant templates (MEM-281), shipped with the build under {@code /chat/assistant-templates/<key>.json}
 * and reviewed in pull requests; nothing syncs from a community repository. The job split and prompt shape of several
 * templates follow lobe-chat-agents (MIT, see the catalogue's NOTICE.md); the text is MemoryOS's own.
 */
public final class AssistantTemplates {
    /** Every template. Removing a key soft-deletes its rows at the next start. */
    static final List<String> KEYS = List.of("email", "translate-vi-en", "meeting-minutes", "weekly-report",
            "document-summary", "policy-qa", "contract-review", "okr-kpi");
    private static final Pattern KEY = Pattern.compile("^[a-z0-9-]{1,64}$");

    private AssistantTemplates() {}

    private static final class Loaded {
        private static final Map<String, AssistantTemplate> TEMPLATES = read();
    }

    /** Every template. */
    public static List<AssistantTemplate> all() {
        return List.copyOf(Loaded.TEMPLATES.values());
    }

    public static Optional<AssistantTemplate> find(@Nullable String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(Loaded.TEMPLATES.get(key));
    }

    private static Map<String, AssistantTemplate> read() {
        var mapper = new ObjectMapper();
        var templates = new LinkedHashMap<String, AssistantTemplate>();
        for (String key : KEYS) {
            String resource = "/chat/assistant-templates/" + key + ".json";
            try (InputStream stream = AssistantTemplates.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("Missing assistant template " + resource);
                templates.put(key, check(key, mapper.readValue(stream, AssistantTemplate.class)));
            } catch (IOException | JacksonException e) {
                throw new IllegalStateException("Unreadable assistant template " + resource, e);
            }
        }
        return templates;
    }

    /** A template must fit every limit an assistant's editor enforces, in both languages. */
    static AssistantTemplate check(String key, AssistantTemplate template) {
        require(KEY.matcher(key).matches() && key.equals(template.key()), key, "key");
        require(template.avatarSeed() != null
                && template.avatarSeed().matches(ChatPersonaService.PersonaInput.AVATAR_SEED_PATTERN), key, "avatarSeed");
        require(template.tools() != null && ChatPersonaService.TOOLS.containsAll(template.tools()), key, "tools");
        require(template.instructions() != null && !template.instructions().isBlank()
                && template.instructions().length() <= 32000, key, "instructions");
        for (var text : List.of(template.vi(), template.en())) {
            require(text != null, key, "language");
            require(text.name() != null && !text.name().isBlank() && text.name().length() <= 200, key, "name");
            require(text.title() != null && !text.title().isBlank()
                    && text.title().length() <= ChatPersonaService.PersonaInput.MAX_TITLE, key, "title");
            require(text.description() != null && !text.description().isBlank() && text.description().length() <= 2000,
                    key, "description");
            require(text.greeting() != null && !text.greeting().isBlank()
                    && text.greeting().length() <= ChatPersonaService.PersonaInput.MAX_GREETING, key, "greeting");
            require(!text.starters().isEmpty() && text.starters().size() <= ChatPersonaService.PersonaInput.MAX_STARTERS
                    && text.starters().stream().allMatch(s -> !s.isBlank() && s.length() <= 1000), key, "starters");
        }
        return template;
    }

    private static void require(boolean valid, String key, String field) {
        if (!valid) throw new IllegalStateException("Assistant template " + key + " has an invalid " + field);
    }
}
