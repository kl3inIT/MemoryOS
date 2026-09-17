package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatTurnOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatModelExecutorToolPolicyTest {
    @Test
    void runPythonRequiresAnAgentThatAllowsTheCodeInterpreter() {
        var allowed = new ChatTurnOptions(true, List.of(), null, null, null, "", true);
        var denied = new ChatTurnOptions(true, List.of(), null, null, null, "", false);
        assertTrue(ChatModelExecutor.pythonAllowed(true, allowed));
        assertFalse(ChatModelExecutor.pythonAllowed(true, denied));
        assertFalse(ChatModelExecutor.pythonAllowed(false, allowed));
    }
}
