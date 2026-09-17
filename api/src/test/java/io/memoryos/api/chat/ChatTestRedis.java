package io.memoryos.api.chat;

import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Redis for encoder tests that run without a Spring context; the staging image. */
final class ChatTestRedis {
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(DockerImageName.parse(
            "redis:8.2.1-alpine@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\n", 1))
            .withStartupTimeout(Duration.ofSeconds(30));
    private static StringRedisTemplate template;

    private ChatTestRedis() {}

    static synchronized StringRedisTemplate template() {
        if (template != null) return template;
        CONTAINER.start();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(CONTAINER.getHost(), CONTAINER.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        return template;
    }
}
