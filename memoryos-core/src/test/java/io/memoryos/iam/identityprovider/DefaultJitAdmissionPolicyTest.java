package io.memoryos.iam.identityprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.iam.JitAllowlistSeeder;
import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DefaultJitAdmissionPolicyTest {

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private JitAllowlistRepository allowlist;
    private DefaultJitAdmissionPolicy policy;
    private JitAllowlistSeeder seeder;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        allowlist = new JitAllowlistRepository(jdbc);
        policy = new DefaultJitAdmissionPolicy(allowlist);
        seeder = new JitAllowlistSeeder(allowlist);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void allowsOnlyAnExactStringAliasPresentInTheDurableAllowlist() {
        transactions.executeWithoutResult(_ -> allowlist.allow("sample", null));

        assertTrue(policy.allows("sample"));
        assertFalse(policy.allows("SAMPLE"));
        assertFalse(policy.allows(" sample "));
        assertFalse(policy.allows(""));
        assertFalse(policy.allows("other"));
        assertFalse(policy.allows(null));
        assertFalse(policy.allows(List.of("sample")));
        assertFalse(policy.allows(Map.of("alias", "sample")));
    }

    @Test
    void seedingIsIdempotentAndDisallowRemovesAdmission() {
        transactions.executeWithoutResult(_ -> seeder.seed(List.of("sample", "sample")));
        transactions.executeWithoutResult(_ -> seeder.seed(List.of("sample")));
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM jit_allowed_provider WHERE alias = 'sample'")
                .query(Long.class).single());
        assertTrue(policy.allows("sample"));

        transactions.executeWithoutResult(_ -> allowlist.disallow("sample"));
        assertFalse(policy.allows("sample"));
    }
}
