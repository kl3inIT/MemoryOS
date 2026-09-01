package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "memoryos.worker.enabled=false",
                "db-scheduler.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis",
                "arconia.dev.services.redis.enabled=false",
                "memoryos.redis.topology-interval=1h",
                "spring.datasource.url=jdbc:h2:mem:redis-unavailable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1",
                "spring.data.redis.password=redis-readiness-secret",
                "spring.data.redis.ssl.enabled=true",
                "spring.data.redis.connect-timeout=100ms",
                "spring.data.redis.timeout=100ms",
                "spring.data.redis.repositories.enabled=false"
        }
)
@AutoConfigureTestRestTemplate
class RedisUnavailableReadinessIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void reportsExecutionTransportUnavailableWithoutLeakingCredentials() {
        var response = http.getForEntity("/actuator/health/readiness", String.class);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().contains("redis-readiness-secret"));
    }
}
