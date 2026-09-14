package io.memoryos.iam.identityprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;

import com.zaxxer.hikari.HikariDataSource;

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

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        allowlist = new JitAllowlistRepository(jdbc);
        policy = new DefaultJitAdmissionPolicy(allowlist);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void allowsOnlyAnExactStringAliasPresentInTheDurableAllowlist() {
        transactions.executeWithoutResult(_ -> allowlist.allow("tasco", null));

        assertTrue(policy.allows("tasco"));
        assertFalse(policy.allows("TASCO"));
        assertFalse(policy.allows(" tasco "));
        assertFalse(policy.allows(""));
        assertFalse(policy.allows("other"));
        assertFalse(policy.allows(null));
        assertFalse(policy.allows(List.of("tasco")));
        assertFalse(policy.allows(Map.of("alias", "tasco")));
    }

    @Test
    void seedingIsIdempotentAndDisallowRemovesAdmission() {
        transactions.executeWithoutResult(_ -> {
            allowlist.allow("tasco", null);
            allowlist.allow("tasco", null);
        });
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM jit_allowed_provider WHERE alias = 'tasco'")
                .query(Long.class).single());
        assertTrue(policy.allows("tasco"));

        transactions.executeWithoutResult(_ -> allowlist.disallow("tasco"));
        assertFalse(policy.allows("tasco"));
    }
}
