package io.memoryos.integration.lark;

import io.memoryos.connector.LarkProviderException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Counts Lark requests by what was asked for and how it ended. Both labels are bounded enumerations; Tenant,
 * credential, space, document and address never appear in a label.
 */
final class LarkProviderMetrics {
    private static final String NAME = "memoryos.connector.lark.request";

    /** What the connector asked Lark for. */
    enum Operation {
        TOKEN, BOT, SCOPES, SPACES, SPACE, NODES, FOLDER, FILES, DOCX, SHEET, CONTENT, PERMISSIONS, USERS,
        CHAT_MEMBERS, DEPARTMENT_MEMBERS, DEPARTMENT_CHILDREN, SPACE_MEMBERS
    }

    private final Map<Operation, Counter> succeeded = new EnumMap<>(Operation.class);
    private final Map<Operation, Map<LarkProviderException.Failure, Counter>> failed = new EnumMap<>(Operation.class);

    LarkProviderMetrics(MeterRegistry registry) {
        for (Operation operation : Operation.values()) {
            succeeded.put(operation, counter(registry, operation, "success"));
            var failures = new EnumMap<LarkProviderException.Failure, Counter>(LarkProviderException.Failure.class);
            for (var failure : LarkProviderException.Failure.values())
                failures.put(failure, counter(registry, operation, failure.name().toLowerCase(Locale.ROOT)));
            failed.put(operation, failures);
        }
    }

    private static Counter counter(MeterRegistry registry, Operation operation, String outcome) {
        return Counter.builder(NAME)
                .description("Lark provider requests by operation and outcome")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome)
                .register(registry);
    }

    <T> T record(Operation operation, Supplier<T> call) {
        try {
            T value = call.get();
            succeeded.get(operation).increment();
            return value;
        } catch (LarkProviderException exception) {
            failed.get(operation).get(exception.failure()).increment();
            throw exception;
        }
    }
}
