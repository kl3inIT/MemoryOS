package io.memoryos.api.chat;

import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.execution.ChatExecutionProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatModelCatalogConfigurationTest {
    @Test
    void explicitDeploymentOptionsOverrideLegacyModelNameDefaults() {
        var persona = new PersonaProperties();
        persona.setModel("custom-deployment");
        var deployment = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null),
                "http://model.internal/v1", -1, -1, true, true, false, true);
        assertEquals(true, deployment.settings().options().get("maxCompletionTokens"));
        assertTrue(deployment.settings().capabilities().toolCalling());
        assertFalse(deployment.settings().capabilities().vision());
        assertTrue(deployment.settings().capabilities().reasoning());
        persona.setModel("gpt-5-mini");
        var legacy = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null),
                "http://model.internal/v1", -1, -1, null, null, null, null);
        assertTrue(legacy.settings().capabilities().vision());
        assertEquals(true, legacy.settings().options().get("maxCompletionTokens"));
        var overridden = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null),
                "http://model.internal/v1", -1, -1, false, false, false, false);
        assertFalse(overridden.settings().capabilities().reasoning());
        assertEquals(false, overridden.settings().options().get("maxCompletionTokens"));
    }

    @Test
    void aCatalogModelImportsItsOwnLimitsAndPricesNotTheExecutionBounds() {
        // MEM-130: staging imported gpt-5-mini as 36096/4096 (context-token-limit + max-output-tokens).
        var persona = new PersonaProperties();
        persona.setModel("gpt-5-mini");
        var known = ChatKnownModels.models().stream().filter(model -> model.modelName().equals("gpt-5-mini")).findFirst().orElseThrow();
        var deployment = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null),
                "http://model.internal/v1", -1, -1, null, null, null, null);
        assertEquals(known.contextWindow(), deployment.settings().contextWindow());
        assertEquals(known.maxOutputTokens(), deployment.settings().maxOutputTokens());
        assertEquals(known.pricing(), deployment.settings().pricing());

        persona.setModel("custom-deployment");
        var unknown = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(null),
                "http://model.internal/v1", -1, -1, null, null, null, null);
        assertEquals(4096 + 1024, unknown.settings().contextWindow());
        assertNull(unknown.settings().pricing());
    }

    @Test
    void finiteCostBudgetRequiresDeploymentPricingAtStartup() {
        var config = new ChatModelCatalogConfiguration();
        var unpriced = new PersonaProperties();
        unpriced.setModel("custom-deployment");
        assertThrows(IllegalArgumentException.class, () -> config.chatDeploymentModel(unpriced, limits(1.0),
                "http://model.internal/v1", -1, -1, null, null, null, null));
        assertNotNull(config.chatDeploymentModel(new PersonaProperties(), limits(1.0),
                "http://model.internal/v1", 0, 0, null, null, null, null).settings().pricing());
    }

    private ChatExecutionProperties limits(Double cost) {
        return new ChatExecutionProperties(2, Duration.ofMinutes(30), Duration.ofSeconds(60), Duration.ofSeconds(30), 2, 1024, 4096, 10000, 10000, cost, 10, Duration.ofSeconds(60));
    }
}
