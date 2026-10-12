package io.memoryos.ingestion;

import java.util.Objects;
import java.util.UUID;

public record DispatchClaim(OperationDelivery delivery, UUID token) {
    public DispatchClaim {
        Objects.requireNonNull(delivery, "delivery must not be null");
        Objects.requireNonNull(token, "token must not be null");
    }
}
