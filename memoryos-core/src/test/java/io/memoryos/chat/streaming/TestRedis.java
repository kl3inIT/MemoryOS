package io.memoryos.chat.streaming;

import java.time.Duration;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** One Redis per test JVM, the staging image; commands time out quickly so an outage test cannot stall the suite. */
public final class TestRedis {
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(DockerImageName.parse(
            "redis:8.10.2-alpine@sha256:3811787313eba226a2ef38658c6ccb91cd5e110edc89c37767de373120a0e5a0"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(30));
    private static StringRedisTemplate template;

    private TestRedis() {}

    public static synchronized StringRedisTemplate template() {
        if (template != null) return template;
        CONTAINER.start();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(CONTAINER.getHost(), CONTAINER.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        return template;
    }

    static GenericContainer<?> container() {
        template();
        return CONTAINER;
    }
}
