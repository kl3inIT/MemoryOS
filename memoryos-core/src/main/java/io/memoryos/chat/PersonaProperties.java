package io.memoryos.chat;

import io.memoryos.chat.prompts.ChatPrompts;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("memoryos.chat.persona")
public class PersonaProperties {
    private String name = "MemoryOS";
    private String instructions = ChatPrompts.DEFAULT_SYSTEM;

    public String getName() { return name; }
    public String getInstructions() { return instructions; }
    public void setName(String name) { this.name = require(name, 200); }
    public void setInstructions(String instructions) { this.instructions = require(instructions, 32000); }

    private static String require(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw ChatException.invalid("Invalid default Persona configuration.");
        }
        return value;
    }
}
