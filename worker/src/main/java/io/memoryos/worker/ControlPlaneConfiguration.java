package io.memoryos.worker;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "db-scheduler.enabled", havingValue = "true", matchIfMissing = true)
class ControlPlaneConfiguration {
    static final String REDIS_TOPOLOGY_TASK = "memoryos-redis-topology-ensure";

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
