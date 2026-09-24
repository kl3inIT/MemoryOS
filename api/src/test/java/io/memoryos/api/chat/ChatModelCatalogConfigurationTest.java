package io.memoryos.api.chat;

import io.memoryos.chat.PersonaProperties;
import io.memoryos.ai.ProviderAdapters;
import io.memoryos.ai.openai.KnownModels;
import io.memoryos.ai.openai.OpenAiProviderAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.memoryos.chat.ChatExecutionProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatModelCatalogConfigurationTest {
    private static final ProviderAdapters ADAPTERS = new ProviderAdapters(List.of(
            new OpenAiProviderAdapter(ObservationRegistry.NOOP, new SimpleMeterRegistry())));

    @Test
    void explicitDeploymentOptionsOverrideLegacyModelNameDefaults() {
        var persona = new PersonaProperties();
        persona.setModel("custom-deployment");
        var deployment = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null), ADAPTERS,
                "http://model.internal/v1", -1, -1, true, true, false, true);
        assertEquals(true, deployment.settings().options().get("maxCompletionTokens"));
        assertTrue(deployment.settings().capabilities().toolCalling());
        assertFalse(deployment.settings().capabilities().vision());
        assertTrue(deployment.settings().capabilities().reasoning());
        persona.setModel("gpt-5-mini");
        var legacy = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null), ADAPTERS,
                "http://model.internal/v1", -1, -1, null, null, null, null);
        assertTrue(legacy.settings().capabilities().vision());
        assertEquals(true, legacy.settings().options().get("maxCompletionTokens"));
        var overridden = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null), ADAPTERS,
                "http://model.internal/v1", -1, -1, false, false, false, false);
        assertFalse(overridden.settings().capabilities().reasoning());
        assertEquals(false, overridden.settings().options().get("maxCompletionTokens"));
    }

    @Test
    void aCatalogModelImportsItsOwnLimitsAndPricesNotTheExecutionBounds() {
        // MEM-130: staging imported gpt-5-mini as 36096/4096 (context-token-limit + max-output-tokens).
        var persona = new PersonaProperties();
        persona.setModel("gpt-5-mini");
        var known = KnownModels.models().stream().filter(model -> model.modelName().equals("gpt-5-mini")).findFirst().orElseThrow();
        var deployment = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null), ADAPTERS,
                "http://model.internal/v1", -1, -1, null, null, null, null);
        assertEquals(known.contextWindow(), deployment.settings().contextWindow());
        assertEquals(known.maxOutputTokens(), deployment.settings().maxOutputTokens());
        assertEquals(known.pricing(), deployment.settings().pricing());

        persona.setModel("custom-deployment");
        var unknown = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null), ADAPTERS,
                "http://model.internal/v1", -1, -1, null, null, null, null);
        // Onyx's defaults, as for a discovered model nobody describes: a 32,000-token window and no output cap.
        assertEquals(32_000, unknown.settings().contextWindow());
        assertNull(unknown.settings().maxOutputTokens());
        assertNull(unknown.settings().pricing());
    }

    @Test
    void finiteCostBudgetRequiresDeploymentPricingAtStartup() {
        var config = new ChatModelCatalogConfiguration();
        var unpriced = new PersonaProperties();
        unpriced.setModel("custom-deployment");
        assertThrows(IllegalArgumentException.class, () -> config.chatDeploymentModel(unpriced, limits(1.0), ADAPTERS,
                "http://model.internal/v1", -1, -1, null, null, null, null));
        assertNotNull(config.chatDeploymentModel(new PersonaProperties(), limits(1.0), ADAPTERS,
                "http://model.internal/v1", 0, 0, null, null, null, null).settings().pricing());
    }

    private ChatExecutionProperties limits(Double cost) {
        return new ChatExecutionProperties(2, Duration.ofMinutes(30), Duration.ofSeconds(60), Duration.ofSeconds(30), 2, 1024, 4096, 10000, 10000, cost, 10, Duration.ofSeconds(60));
    }
}
