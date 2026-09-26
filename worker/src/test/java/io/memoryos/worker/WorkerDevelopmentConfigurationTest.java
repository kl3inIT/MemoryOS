package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

class WorkerDevelopmentConfigurationTest {

    @Test
    void neverStartsARedisDevServiceOfItsOwn() throws IOException {
        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader().load(
                "worker-development",
                new ClassPathResource("application-development.yaml"));

        assertThat(property(propertySources, "arconia.dev.services.redis.port")).isNull();
        assertThat(ClassUtils.isPresent(
                "io.arconia.dev.services.redis.RedisDevServicesAutoConfiguration",
                getClass().getClassLoader())).isFalse();
    }

    private static Object property(List<PropertySource<?>> propertySources, String name) {
        return propertySources.stream()
                .map(propertySource -> propertySource.getProperty(name))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
