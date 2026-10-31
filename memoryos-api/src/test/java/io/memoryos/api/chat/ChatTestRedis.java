package io.memoryos.api.chat;

import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Redis for chat tests, with or without a Spring context; the staging image. */
final class ChatTestRedis {
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(DockerImageName.parse(
            "redis:8.10.2-alpine@sha256:3811787313eba226a2ef38658c6ccb91cd5e110edc89c37767de373120a0e5a0"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\n", 1))
            .withStartupTimeout(Duration.ofSeconds(30));
    private static StringRedisTemplate template;

    private ChatTestRedis() {}

    static synchronized void configure(DynamicPropertyRegistry registry) {
        CONTAINER.start();
        registry.add("spring.data.redis.host", CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> CONTAINER.getMappedPort(6379));
    }

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
