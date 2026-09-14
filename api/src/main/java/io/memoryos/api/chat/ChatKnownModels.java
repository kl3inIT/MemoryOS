package io.memoryos.api.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memoryos.chat.catalog.ChatProviderAdapter.KnownModel;
import io.memoryos.chat.catalog.ModelSettings;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Installed model metadata shipped with the build. Regenerate with
 * {@code node scripts/sync-chat-known-models.mjs}; the resource records its own provenance.
 */
final class ChatKnownModels {
    private static final String RESOURCE = "/chat/known-models.json";

    private ChatKnownModels() {}

    private static final class Loaded {
        private static final List<KnownModel> MODELS = read();
    }

    static List<KnownModel> models() { return Loaded.MODELS; }

    private static List<KnownModel> read() {
        try (InputStream stream = ChatKnownModels.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing model metadata " + RESOURCE);
            JsonNode root = new ObjectMapper().readTree(stream);
            var models = new ArrayList<KnownModel>();
            for (JsonNode node : root.path("models")) {
                models.add(new KnownModel(node.path("modelName").asText(),
                        node.path("contextWindow").asInt(), node.path("maxOutputTokens").asInt(),
                        new ModelSettings.Capabilities(true, node.path("toolCalling").asBoolean(),
                                node.path("vision").asBoolean(), node.path("reasoning").asBoolean()),
                        new ModelSettings.Pricing(node.path("inputPerMillion").asDouble(),
                                node.path("outputPerMillion").asDouble())));
            }
            if (models.isEmpty()) throw new IllegalStateException("Empty model metadata " + RESOURCE);
            return List.copyOf(models);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable model metadata " + RESOURCE, e);
        }
    }
}
