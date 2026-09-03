package io.memoryos.ingestion;

import java.time.Duration;
import java.util.List;

public interface OperationDispatchPort {

    List<DispatchClaim> claim(OperationWorkload workload, int batchSize);

    boolean recordPublished(DispatchClaim claim, String redisMessageId, Duration rediscoveryDelay);

    boolean defer(DispatchClaim claim, String errorCode, Duration backoff);

    boolean reclaimable(OperationDelivery delivery);
}
