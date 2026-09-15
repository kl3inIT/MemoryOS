package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelSettingsTest {
    @Test
    void nullOptionsProduceValidationErrorsAndValidOptionsAreCopied() {
        var options = new HashMap<String, Object>();
        options.put("temperature", null);
        assertThrows(ChatException.class, () -> settings(options));
        options.clear();
        options.put(null, 1);
        assertThrows(ChatException.class, () -> settings(options));
        options.clear();
        options.put("temperature", 0.5);
        var settings = settings(options);
        options.put("temperature", 1.0);
        assertEquals(0.5, settings.options().get("temperature"));
    }

    @Test
    void profileIdentityCannotBeMissingOrBlank() {
        for (String profile : new String[] { null, "", " " })
            assertThrows(ChatException.class, () -> new ModelSettings(4096, 1024,
                    new ModelSettings.Capabilities(true, false, false, false), Map.of(), null, profile));
    }

    private ModelSettings settings(Map<String, Object> options) {
        return new ModelSettings(4096, 1024, new ModelSettings.Capabilities(true, false, false, false), options, null, "openai-o200k-v1");
    }
}
