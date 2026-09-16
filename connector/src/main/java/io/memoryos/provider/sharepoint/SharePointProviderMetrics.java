package io.memoryos.provider.sharepoint;

import io.memoryos.connector.SharePointProviderException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;

/**
 * Counts Microsoft requests by what was asked for and how it ended. Both labels are bounded enumerations;
 * Tenant, credential, site, item and address never appear in a label.
 */
final class SharePointProviderMetrics {
    private static final String NAME = "memoryos.connector.sharepoint.request";

    /** What the connector asked Microsoft for. */
    enum Operation { TOKEN, ROOT_SITE, SITE, LIBRARIES, FOLDER, SITES, DELTA, CHILDREN, ITEM, CONTENT }

    private final Map<Operation, Counter> succeeded = new EnumMap<>(Operation.class);
    private final Map<Operation, Map<SharePointProviderException.Failure, Counter>> failed =
            new EnumMap<>(Operation.class);

    SharePointProviderMetrics(MeterRegistry registry) {
        for (Operation operation : Operation.values()) {
            succeeded.put(operation, counter(registry, operation, "success"));
            var failures = new EnumMap<SharePointProviderException.Failure, Counter>(SharePointProviderException.Failure.class);
            for (var failure : SharePointProviderException.Failure.values()) {
                failures.put(failure, counter(registry, operation, failure.name().toLowerCase(java.util.Locale.ROOT)));
            }
            failed.put(operation, failures);
        }
    }

    private static Counter counter(MeterRegistry registry, Operation operation, String outcome) {
        return Counter.builder(NAME)
                .description("SharePoint provider requests by operation and outcome")
                .tag("operation", operation.name().toLowerCase(java.util.Locale.ROOT))
                .tag("outcome", outcome)
                .register(registry);
    }

    <T> T record(Operation operation, java.util.function.Supplier<T> call) {
        try {
            T value = call.get();
            succeeded.get(operation).increment();
            return value;
        } catch (SharePointProviderException exception) {
            failed.get(operation).get(exception.failure()).increment();
            throw exception;
        }
    }
}
