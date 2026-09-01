package io.memoryos.worker;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;

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
    @ConditionalOnProperty(
            name = "memoryos.redis.topology-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    RecurringTask<Void> redisTopologyTask(
            RedisExecutionTopology topology,
            RedisExecutionProperties properties
    ) {
        return Tasks.recurring(REDIS_TOPOLOGY_TASK, FixedDelay.of(properties.topologyInterval()))
                .execute((_, _) -> topology.reconcileTopology());
    }
}
