package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "memoryos.worker.enabled=false",
                "db-scheduler.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis",
                "arconia.dev.services.redis.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:worker-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
class WorkerApplicationSmokeTest {

    @Autowired
    private ObjectProvider<RedisStreamWorker> redisStreamWorker;

    @Autowired
    private RedisExecutionProperties redisExecutionProperties;

    @Test
    void contextLoadsWithExecutionDisabled() {
        assertNull(redisStreamWorker.getIfAvailable());
        assertNotNull(redisExecutionProperties);
    }
}
