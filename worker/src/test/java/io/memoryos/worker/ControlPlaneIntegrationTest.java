package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.event.ExecutionInterceptor;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "memoryos.worker.enabled=false",
                "memoryos.redis.topology-interval=1h",
                "memoryos.redis.relay-interval=100ms",
                "memoryos.redis.ingestion.stream=memoryos:test:control:ingestion",
                "memoryos.redis.ingestion.group=memoryos-test-control-ingestion-workers",
                "memoryos.redis.cleanup.stream=memoryos:test:control:cleanup",
                "memoryos.redis.cleanup.group=memoryos-test-control-cleanup-workers",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/migration/V1__create_identity_tables.sql,"
                        + "classpath:db/migration/V2__create_initial_organization_and_sessions.sql,"
                        + "classpath:db/migration/V3__create_organization_invitations.sql,"
                        + "classpath:db/migration/V4__collapse_workspace_into_organization.sql,"
                        + "classpath:db/migration/V5__create_file_source_and_document_schema.sql,"
                        + "classpath:db/migration/V6__cut_over_organization_to_tenant.sql,"
                        + "classpath:db/migration/V7__create_scheduler_control_plane.sql,"
                        + "classpath:db/migration/V8__cut_over_operations_to_redis_streams.sql,"
                        + "classpath:db/migration/V9__cut_over_file_content_to_object_storage.sql,"
                        + "classpath:db/migration/V10__persist_operation_trace_origins.sql,"
                        + "classpath:db/migration/V11__add_document_extraction_artifacts.sql,"
                        + "classpath:db/migration/V12__use_current_documents.sql",
                "spring.data.redis.repositories.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis,dbScheduler",
                "db-scheduler.enabled=true",
                "db-scheduler.scheduler-name=mem45-integration",
                "db-scheduler.threads=2",
                "db-scheduler.missed-heartbeats-limit=4",
                "db-scheduler.heartbeat-interval=1s",
                "db-scheduler.delay-startup-until-context-ready=true",
                "db-scheduler.shutdown-max-wait=5s"
        }
)
@AutoConfigureTestRestTemplate
@Import(ControlPlaneIntegrationTest.VirtualThreadProbeConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlPlaneIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private RedisExecutionProperties redisProperties;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private AtomicBoolean topologyTaskRanOnVirtualThread;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void registersExecutesAndRecoversTheTopologyControlTask() {
        await(() -> successfulExecutionTime(ControlPlaneConfiguration.REDIS_TOPOLOGY_TASK) != null
                && successfulExecutionTime(ControlPlaneConfiguration.INACTIVE_INDEX_CANCELLATION_TASK) != null
                && successfulExecutionTime(ControlPlaneConfiguration.INGESTION_RELAY_TASK) != null
                && successfulExecutionTime(ControlPlaneConfiguration.CLEANUP_RELAY_TASK) != null);
        assertTrue(topologyTaskRanOnVirtualThread.get());
        Instant firstSuccess = successfulExecutionTime(ControlPlaneConfiguration.REDIS_TOPOLOGY_TASK);
        assertTrue(groupExists(redisProperties.ingestion()));
        assertTrue(groupExists(redisProperties.cleanup()));
        assertEquals(
                HttpStatus.OK,
                http.getForEntity("/actuator/health/readiness", String.class).getStatusCode()
        );

        jdbcClient.sql("""
                        UPDATE scheduled_tasks
                        SET picked = TRUE,
                            picked_by = 'terminated-scheduler',
                            last_heartbeat = CURRENT_TIMESTAMP - INTERVAL '1 minute',
                            execution_time = CURRENT_TIMESTAMP - INTERVAL '1 second',
                            version = version + 1
                        WHERE task_name = :taskName
                        """)
                .param("taskName", ControlPlaneConfiguration.REDIS_TOPOLOGY_TASK)
                .update();

        await(() -> {
            Instant recoveredSuccess = successfulExecutionTime(ControlPlaneConfiguration.REDIS_TOPOLOGY_TASK);
            return recoveredSuccess != null && recoveredSuccess.isAfter(firstSuccess);
        });
    }

    @Test
    void twoSchedulersNeverExecuteOneRecurringTaskConcurrently() throws Exception {
        var running = new AtomicInteger();
        var maximumConcurrency = new AtomicInteger();
        var executions = new AtomicInteger();
        var executed = new CountDownLatch(1);
        var task = Tasks.recurring("mem45-cluster-singleton-test", FixedDelay.ofHours(1))
                .execute((_, _) -> {
                    int concurrent = running.incrementAndGet();
                    maximumConcurrency.accumulateAndGet(concurrent, Math::max);
                    try {
                        LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
                        executions.incrementAndGet();
                    } finally {
                        running.decrementAndGet();
                        executed.countDown();
                    }
                });

        Scheduler first = clusterScheduler("mem45-cluster-a", task);
        Scheduler second = clusterScheduler("mem45-cluster-b", task);
        try {
            first.start();
            second.start();
            assertTrue(executed.await(5, TimeUnit.SECONDS));
            LockSupport.parkNanos(Duration.ofMillis(500).toNanos());
            assertEquals(1, executions.get());
            assertEquals(1, maximumConcurrency.get());
        } finally {
            first.stop();
            second.stop();
        }
    }

    private Scheduler clusterScheduler(String name, RecurringTask<Void> task) {
        return Scheduler.create(dataSource)
                .startTasks(task)
                .threads(1)
                .pollingInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofSeconds(1))
                .missedHeartbeatsLimit(4)
                .schedulerName(new SchedulerName.Fixed(name))
                .build();
    }

    private Instant successfulExecutionTime(String taskName) {
        return jdbcClient.sql("""
                        SELECT last_success FROM scheduled_tasks
                        WHERE task_name = :taskName
                        """)
                .param("taskName", taskName)
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    private boolean groupExists(RedisExecutionProperties.Workload workload) {
        return redis.opsForStream()
                .groups(workload.stream())
                .stream()
                .anyMatch(group -> workload.group().equals(group.groupName()));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("control-plane condition did not converge within 15 seconds");
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class VirtualThreadProbeConfiguration {

        @Bean
        AtomicBoolean topologyTaskRanOnVirtualThread() {
            return new AtomicBoolean();
        }

        @Bean
        ExecutionInterceptor virtualThreadProbe(AtomicBoolean topologyTaskRanOnVirtualThread) {
            return (taskInstance, executionContext, chain) -> {
                if (ControlPlaneConfiguration.REDIS_TOPOLOGY_TASK.equals(taskInstance.getTaskName())) {
                    topologyTaskRanOnVirtualThread.set(Thread.currentThread().isVirtual());
                }
                return chain.proceed(taskInstance, executionContext);
            };
        }
    }

}
