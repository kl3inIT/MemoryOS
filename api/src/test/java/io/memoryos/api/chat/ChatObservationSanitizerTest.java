package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NullMarked;

@NullMarked
class ChatObservationSanitizerTest {
    @Test
    void nativeToolLifecycleExportsTimingAndStatusWithoutPayloadsAtStartErrorOrStop() {
        var registry = ObservationRegistry.create();
        var sanitizer = new ChatObservationSanitizer();
        var lifecycle = new ArrayList<String>();
        registry.observationConfig().observationHandler(sanitizer).observationFilter(sanitizer)
                .observationHandler(new ObservationHandler<>() {
                    @Override public boolean supportsContext(Observation.Context context) { return true; }
                    @Override public void onStart(Observation.Context context) { check(context); lifecycle.add("start"); }
                    @Override public void onError(Observation.Context context) { check(context); lifecycle.add("error"); }
                    @Override public void onStop(Observation.Context context) { check(context); lifecycle.add("stop"); }
                    private void check(Observation.Context context) {
                        assertNull(context.getHighCardinalityKeyValue("payload"));
                        assertNull(context.getHighCardinalityKeyValue("result"));
                        assertNull(context.getHighCardinalityKeyValue("error_message"));
                        var tool = context.getLowCardinalityKeyValue("toolName");
                        assertNotNull(tool);
                        assertEquals("searchKnowledge", tool.getValue());
                        if (context.getError() != null) assertFalse(context.getError().toString().contains("PRIVATE"));
                    }
                });
        var observation = Observation.createNotStarted("tool call", registry)
                .lowCardinalityKeyValue("toolName", "searchKnowledge").highCardinalityKeyValue("payload", "PRIVATE QUESTION").start();
        observation.highCardinalityKeyValue("result", "PRIVATE SOURCE");
        observation.highCardinalityKeyValue("error_message", "PRIVATE ERROR");
        observation.error(new IllegalStateException("PRIVATE FAILURE"));
        observation.lowCardinalityKeyValue("status", "error").stop();
        assertEquals(List.of("start", "error", "stop"), lifecycle);
    }
}
