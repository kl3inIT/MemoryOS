package io.memoryos.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "memoryos.worker.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:worker-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        }
)
class WorkerApplicationSmokeTest {

    @Test
    void contextLoadsWithPersistenceRuntimeAndSchedulingDisabled() {
    }
}
