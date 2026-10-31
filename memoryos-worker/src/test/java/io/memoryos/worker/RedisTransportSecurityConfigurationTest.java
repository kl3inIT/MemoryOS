package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RedisTransportSecurityConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    RedisPropertiesTestConfiguration.class,
                    RedisTransportSecurityConfiguration.class
            );

    @Test
    void rejectsCredentialsWithoutTlsWithoutLeakingThem() {
        contextRunner
                .withPropertyValues("spring.data.redis.password=redis-security-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Redis credentials require an encrypted TLS connection")
                            .hasMessageNotContaining("redis-security-secret");
                });
    }

    @Test
    void permitsCredentialsWithTls() {
        contextRunner
                .withPropertyValues(
                        "spring.data.redis.username=memoryos",
                        "spring.data.redis.password=redis-security-secret",
                        "spring.data.redis.ssl.enabled=true"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void permitsAnonymousCleartextForLocalDevelopment() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataRedisProperties.class)
    static class RedisPropertiesTestConfiguration {
    }
}
