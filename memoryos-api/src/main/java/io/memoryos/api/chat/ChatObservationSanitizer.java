package io.memoryos.api.chat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationHandler;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.NullMarked;

/** Native tool observations retain timing/status without exporting arguments, retrieved content or error payloads. */
@Component
@NullMarked
final class ChatObservationSanitizer implements ObservationHandler<Observation.Context>, ObservationFilter, Ordered {
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsContext(Observation.Context context) { return "tool call".equals(context.getName()); }
    @Override public void onStart(Observation.Context context) { sanitize(context); }
    @Override public void onError(Observation.Context context) { sanitize(context); }
    @Override public Observation.Context map(Observation.Context context) {
        if (supportsContext(context)) sanitize(context);
        return context;
    }
    private static void sanitize(Observation.Context context) {
        context.removeHighCardinalityKeyValue("payload");
        context.removeHighCardinalityKeyValue("result");
        context.removeHighCardinalityKeyValue("error_message");
        if (context.getError() != null) context.setError(new IllegalStateException("CHAT_TOOL_FAILED"));
    }
}
