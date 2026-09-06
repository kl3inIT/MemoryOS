package io.memoryos.worker;

import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class RedisTransportSecurityConfiguration {

    RedisTransportSecurityConfiguration(DataRedisProperties properties) {
        boolean credentialsConfigured = StringUtils.hasText(properties.getUsername())
                || StringUtils.hasText(properties.getPassword());
        if (credentialsConfigured && !properties.getSsl().isEnabled()) {
            throw new IllegalStateException("Redis credentials require an encrypted TLS connection");
        }
    }
}
