package io.memoryos.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "memoryos.redis.topology-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(RedisExecutionProperties.class)
class RedisTopologyConfiguration {

    @Bean
    RedisExecutionTopology redisExecutionTopology(
            StringRedisTemplate redis,
            RedisExecutionProperties properties
    ) {
        return new RedisExecutionTopology(redis, properties);
    }
}
