package io.memoryos.usage;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The Tenant's AI budget is spent. Not a fault: the caller may try again once the budget frees, so the API answers
 * 429 with {@code Retry-After}, as Onyx does.
 */
public final class AiUsageLimitException extends BusinessException {
    private final AiUsageLimitService.Breach breach;

    AiUsageLimitException(AiUsageLimitService.Breach breach, String message) {
        super("AI_USAGE_LIMIT_EXCEEDED", FailureCategory.LIMIT_EXCEEDED, message, message);
        this.breach = breach;
    }

    public AiUsageLimitScope scope() {
        return breach.scope();
    }

    public @Nullable String groupName() {
        return breach.groupName();
    }

    public Instant resetsAt() {
        return breach.resetsAt();
    }
}
