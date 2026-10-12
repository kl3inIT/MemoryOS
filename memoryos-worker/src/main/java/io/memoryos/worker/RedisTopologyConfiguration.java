package io.memoryos.worker;

import io.memoryos.ingestion.OperationDispatchPort;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisExecutionProperties.class)
class RedisTopologyConfiguration {

    @Bean
    RedisExecutionTopology redisExecutionTopology(
            StringRedisTemplate redis,
            RedisExecutionProperties properties
    ) {
        return new RedisExecutionTopology(redis, properties);
    }

    @Bean
    RedisOperationRelay redisOperationRelay(
            StringRedisTemplate redis,
            OperationDispatchPort dispatch,
            RedisExecutionProperties properties,
            RedisExecutionMetrics metrics,
            OpenTelemetry telemetry
    ) {
        return new RedisOperationRelay(redis, dispatch, properties, metrics, telemetry);
    }
}
