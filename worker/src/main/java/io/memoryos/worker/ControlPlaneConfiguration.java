package io.memoryos.worker;

import io.memoryos.audit.AuditRetention;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.memoryos.library.UserFileMaintenance;
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
import io.memoryos.library.ChatLibraryArchiveService;

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
    RecurringTask<Void> sharePointSelectionValidationRelayTask(RedisOperationRelay relay, RedisExecutionProperties properties) {
        return Tasks.recurring("memoryos-redis-sharepoint-selection-relay-v1", FixedDelay.of(properties.relayInterval()))
                .execute((_, _) -> relay.relay(OperationWorkload.SHAREPOINT_SELECTION_VALIDATION));
    }

    @Bean
    RecurringTask<Void> dueSourceSyncTask(io.memoryos.connector.ConnectorSyncPort sync) {
        return Tasks.recurring("memoryos-due-source-sync-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> sync.enqueueDue(16));
    }

    @Bean
    RecurringTask<Void> expiredChatUploadTask(UserFileMaintenance files) {
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
    RecurringTask<Void> chatSessionPurgeTask(io.memoryos.chat.application.ChatSessionPurgeService sessions) {
        return Tasks.recurring("memoryos-chat-session-purge-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> sessions.purge());
    }

    /**
     * Temporary conversations delete themselves a while after their last message (MEM-153); the purge task
     * above then removes their rows and hands their uploads to the file work.
     */
    @Bean
    RecurringTask<Void> chatTemporarySessionTask(io.memoryos.chat.application.ChatSessionPurgeService sessions) {
        return Tasks.recurring("memoryos-chat-temporary-session-v1", FixedDelay.of(Duration.ofMinutes(5)))
                .execute((_, _) -> sessions.expireTemporary());
    }

    /**
     * One export per tick, plus the sweep that releases an expired one, exactly as the library archive task
     * works; an export reads a whole account, so one at a time is deliberate.
     */
    @Bean
    RecurringTask<Void> chatExportTask(io.memoryos.chat.application.ChatExportService exports) {
        return Tasks.recurring("memoryos-chat-export-v1", FixedDelay.of(Duration.ofSeconds(10)))
                .execute((_, _) -> {
                    exports.buildNext();
                    exports.sweepExpired();
                });
    }

    /** Each Tenant's retention policy, applied in batches; an hour is far finer than a policy in days. */
    @Bean
    RecurringTask<Void> chatRetentionPolicyTask(io.memoryos.chat.application.ChatSessionPurgeService sessions) {
        return Tasks.recurring("memoryos-chat-retention-policy-v1", FixedDelay.of(Duration.ofHours(1)))
                .execute((_, _) -> sessions.applyRetentionPolicies());
    }

    @Bean
    RecurringTask<Void> chatArtifactCleanupTask(io.memoryos.chat.application.ChatArtifactCleanupService artifacts) {
        return Tasks.recurring("memoryos-chat-artifact-cleanup-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> artifacts.cleanup());
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

    /** Deletes audit events past their retention (ADR 0013), a batch at a time until none remain. */
    @Bean
    RecurringTask<Void> auditRetentionTask(AuditRetention retention) {
        return Tasks.recurring("memoryos-audit-retention-v1", FixedDelay.of(Duration.ofHours(1)))
                .execute((_, _) -> {
                    for (int batch = 0; batch < 20 && retention.sweep() == AuditRetention.BATCH; batch++) {
                        // A full batch means more may be waiting.
                    }
                });
    }

    /** Builds requested usage reports; a few per run, so a queue drains without holding the scheduler thread. */
    @Bean
    RecurringTask<Void> usageReportTask(io.memoryos.usage.report.UsageReportService reports) {
        return Tasks.recurring("memoryos-ai-usage-report-v1", FixedDelay.of(Duration.ofSeconds(5)))
                .execute((_, _) -> {
                    for (int built = 0; built < 4 && reports.buildNext(); built++) {
                        // Each call builds and stores one report.
                    }
                });
    }

    /**
     * Queues the byte release of uploads whose trash window has passed (MEM-152 phase 4); the existing file
     * DELETE work then owns the release itself.
     */
    @Bean
    RecurringTask<Void> chatLibraryTrashTask(UserFileMaintenance files) {
        return Tasks.recurring("memoryos-chat-library-trash-v1", FixedDelay.of(Duration.ofMinutes(5)))
                .execute((_, _) -> files.queueTrashPurges(100));
    }

    /** Packs requested library archives and releases the ones that expired (MEM-152). */
    @Bean
    RecurringTask<Void> chatLibraryArchiveTask(ChatLibraryArchiveService archives) {
        return Tasks.recurring("memoryos-chat-library-archive-v1", FixedDelay.of(Duration.ofSeconds(5)))
                .execute((_, _) -> {
                    for (int packed = 0; packed < 4 && archives.buildNext(); packed++) {
                        // Each call packs and stores one archive.
                    }
                    archives.sweepExpired();
                });
    }

    @Bean
    RecurringTask<Void> searchProjectionTask(SearchProjectionMaintenance maintenance) {
        return Tasks.recurring("memoryos-search-projection-reconcile-v1", FixedDelay.of(Duration.ofMinutes(1)))
                .execute((_, _) -> maintenance.reconcile());
    }

    /**
     * MEM-135: feeds the FUTURE index being rebuilt a bounded window at a time from stored chunks, and switches an
     * automatic rebuild (a new chunk convention) once it holds every document. All state is in PostgreSQL, so a
     * restarted worker resumes.
     */
    @Bean
    RecurringTask<Void> searchRebuildTask(SearchProjectionMaintenance maintenance,
            io.memoryos.retrieval.settings.SearchSettingsService settings) {
        return Tasks.recurring("memoryos-search-rebuild-v1", FixedDelay.of(Duration.ofSeconds(5)))
                .execute((_, _) -> {
                    maintenance.rebuild();
                    settings.switchAutomaticWhenComplete();
                });
    }

    /** MEM-135: deletes the index of a PAST search generation once its retention ended, with a recount. */
    @Bean
    RecurringTask<Void> searchGenerationCleanupTask(io.memoryos.retrieval.settings.SearchSettingsService settings) {
        return Tasks.recurring("memoryos-search-generation-cleanup-v1", FixedDelay.of(Duration.ofHours(1)))
                .execute((_, _) -> settings.cleanupExpired());
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
