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
        var deployment = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(Double.MAX_VALUE),
                "http://model.internal/v1", -1, -1, true, true, false, true);
        assertEquals(true, deployment.settings().options().get("maxCompletionTokens"));
        assertTrue(deployment.settings().capabilities().toolCalling());
        assertFalse(deployment.settings().capabilities().vision());
        assertTrue(deployment.settings().capabilities().reasoning());
        persona.setModel("gpt-5-mini");
        var legacy = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(Double.MAX_VALUE),
                "http://model.internal/v1", -1, -1, null, null, null, null);
        assertTrue(legacy.settings().capabilities().vision());
        assertEquals(true, legacy.settings().options().get("maxCompletionTokens"));
        var overridden = new ChatModelCatalogConfiguration().chatDeploymentModel(persona, limits(Double.MAX_VALUE),
                "http://model.internal/v1", -1, -1, false, false, false, false);
        assertFalse(overridden.settings().capabilities().reasoning());
        assertEquals(false, overridden.settings().options().get("maxCompletionTokens"));
    }

    @Test
    void finiteCostBudgetRequiresDeploymentPricingAtStartup() {
        var config = new ChatModelCatalogConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.chatDeploymentModel(new PersonaProperties(), limits(1),
                "http://model.internal/v1", -1, -1, null, null, null, null));
        assertNotNull(config.chatDeploymentModel(new PersonaProperties(), limits(1),
                "http://model.internal/v1", 0, 0, null, null, null, null).settings().pricing());
    }

    private ChatExecutionProperties limits(double cost) {
        return new ChatExecutionProperties(2, Duration.ofSeconds(30), 2, 1024, 4096, 10000, 10000, cost);
    }
}
