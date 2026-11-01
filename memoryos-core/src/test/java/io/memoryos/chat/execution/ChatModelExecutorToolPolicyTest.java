package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatTurnOptions;
import org.junit.jupiter.api.Test;

class ChatModelExecutorToolPolicyTest {
    @Test
    void runPythonRequiresAnAgentThatAllowsTheCodeInterpreter() {
        var allowed = ChatTurnOptions.builder().build();
        var denied = ChatTurnOptions.builder()
                .codeInterpreter(false)
                .build();
        assertTrue(ChatModelExecutor.pythonAllowed(true, allowed));
        assertFalse(ChatModelExecutor.pythonAllowed(true, denied));
        assertFalse(ChatModelExecutor.pythonAllowed(false, allowed));
    }
}
