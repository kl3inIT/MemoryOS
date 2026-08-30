package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "memoryos.worker.enabled=false",
                "db-scheduler.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis",
                "memoryos.redis.topology-enabled=false",
                "arconia.dev.services.redis.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:worker-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
class WorkerApplicationSmokeTest {

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor applicationTaskExecutor;

    @Test
    void contextLoadsWithPersistenceRuntimeAndSchedulingDisabled() throws Exception {
        assertTrue(applicationTaskExecutor
                .submit(() -> Thread.currentThread().isVirtual())
                .get(5, TimeUnit.SECONDS));
    }
}
