package io.memoryos.worker;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;

import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;

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
}
