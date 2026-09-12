package io.memoryos.worker;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.application.SearchProjectionMaintenance;
import io.memoryos.objectstorage.ObjectUploadCleanupPort;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "db-scheduler.enabled", havingValue = "true", matchIfMissing = true)
class ControlPlaneConfiguration {
    static final String REDIS_TOPOLOGY_TASK = "memoryos-redis-execution-topology-reconcile-v1";
    static final String INACTIVE_INDEX_CANCELLATION_TASK = "memoryos-inactive-index-cancellation-v1";
    static final String INGESTION_RELAY_TASK = "memoryos-redis-ingestion-relay-v1";
    static final String CLEANUP_RELAY_TASK = "memoryos-redis-cleanup-relay-v1";
    static final String ABANDONED_OBJECT_UPLOAD_CLEANUP_TASK = "memoryos-abandoned-object-upload-cleanup-v1";
    static final String DB_SCHEDULER_TASK_EXECUTOR = "dbSchedulerTaskExecutor";

    @Bean(name = DB_SCHEDULER_TASK_EXECUTOR, defaultCandidate = false, destroyMethod = "close")
    ExecutorService dbSchedulerTaskExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("memoryos-db-scheduler-", 0).factory()
        );
    }

    @Bean
    DbSchedulerCustomizer dbSchedulerCustomizer(
            @Qualifier(DB_SCHEDULER_TASK_EXECUTOR) ExecutorService taskExecutor
    ) {
        return new DbSchedulerCustomizer() {
            @Override
            public Optional<ExecutorService> executorService() {
                return Optional.of(taskExecutor);
            }
        };
    }

    @Bean
    RecurringTask<Void> redisTopologyTask(
            RedisExecutionTopology topology,
            RedisExecutionProperties properties
    ) {
        return Tasks.recurring(REDIS_TOPOLOGY_TASK, FixedDelay.of(properties.topologyInterval()))
                .execute((_, _) -> topology.reconcileTopology());
    }

    @Bean
    RecurringTask<Void> inactiveIndexCancellationTask(
            OperationDispatchPort dispatch,
            RedisExecutionProperties properties
    ) {
        return Tasks.recurring(INACTIVE_INDEX_CANCELLATION_TASK, FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> dispatch.cancelInactiveTenantIndexing(properties.ingestion().batchSize()));
    }

    @Bean
    RecurringTask<Void> ingestionRelayTask(
            RedisOperationRelay relay,
            RedisExecutionProperties properties
    ) {
        return Tasks.recurring(INGESTION_RELAY_TASK, FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> relay.relay(OperationWorkload.INGESTION));
    }

    @Bean
    RecurringTask<Void> cleanupRelayTask(
            RedisOperationRelay relay,
            RedisExecutionProperties properties
    ) {
        return Tasks.recurring(CLEANUP_RELAY_TASK, FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> relay.relay(OperationWorkload.CLEANUP));
    }

    @Bean
    RecurringTask<Void> sourceSyncRelayTask(RedisOperationRelay relay, RedisExecutionProperties properties) {
        return Tasks.recurring("memoryos-redis-source-sync-relay-v1", FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> relay.relay(OperationWorkload.SOURCE_SYNC));
    }

    @Bean
    RecurringTask<Void> selectionValidationRelayTask(RedisOperationRelay relay, RedisExecutionProperties properties) {
        return Tasks.recurring("memoryos-redis-selection-validation-relay-v1", FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> relay.relay(OperationWorkload.GOOGLE_DRIVE_SELECTION_VALIDATION));
    }

    @Bean
    RecurringTask<Void> dueSourceSyncTask(io.memoryos.connector.ConnectorSyncPort sync) {
        return Tasks.recurring("memoryos-due-source-sync-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> sync.enqueueDue(16));
    }

    @Bean
    RecurringTask<Void> expiredChatUploadTask(io.memoryos.chat.persistence.JdbcUserFileWorkRepository files) {
        return Tasks.recurring("memoryos-expired-chat-upload-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> files.expireUploads(100));
    }

    @Bean
    RecurringTask<Void> sourceRunHistoryRetentionTask(io.memoryos.connector.SourceRunHistoryMaintenance history) {
        return Tasks.recurring("memoryos-source-run-history-retention-v1", FixedDelay.of(Duration.ofHours(1)))
                .execute((_, _) -> history.pruneHistory(100));
    }

    @Bean
    RecurringTask<Void> abandonedObjectWriteCleanupTask(io.memoryos.objectstorage.ObjectWriteService writes) {
        return Tasks.recurring("memoryos-abandoned-object-write-cleanup-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> writes.cleanup(16));
    }

    @Bean
    RecurringTask<Void> extractionArtifactCleanupTask(ExtractionArtifactPort artifacts) {
        return Tasks.recurring("memoryos-extraction-artifact-cleanup-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> artifacts.cleanup());
    }

    @Bean
    RecurringTask<Void> searchRelayTask(RedisOperationRelay relay, RedisExecutionProperties properties) {
        return Tasks.recurring("memoryos-redis-search-relay-v1", FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> relay.relay(OperationWorkload.SEARCH));
    }

    @Bean
    RecurringTask<Void> userFileRelayTask(RedisOperationRelay relay, RedisExecutionProperties properties) {
        return Tasks.recurring("memoryos-redis-user-file-relay-v1", FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> relay.relay(OperationWorkload.USER_FILE));
    }

    @Bean
    RecurringTask<Void> searchProjectionTask(SearchProjectionMaintenance maintenance) {
        return Tasks.recurring("memoryos-search-projection-reconcile-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> maintenance.reconcile());
    }

    @Bean
    AbandonedObjectUploadCleanupTask abandonedObjectUploadCleanupTask(ObjectUploadCleanupPort cleanup) {
        return new AbandonedObjectUploadCleanupTask(cleanup);
    }

    @Bean
    RecurringTask<Void> abandonedObjectUploadCleanupRecurringTask(AbandonedObjectUploadCleanupTask cleanup) {
        return Tasks.recurring(
                        ABANDONED_OBJECT_UPLOAD_CLEANUP_TASK,
                        FixedDelay.of(Duration.ofMinutes(1))
                )
                .execute((_, _) -> cleanup.execute());
    }
}
