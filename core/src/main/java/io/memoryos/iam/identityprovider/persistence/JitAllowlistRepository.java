package io.memoryos.iam.identityprovider.persistence;

import io.memoryos.iam.identity.ActorId;

import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable JIT admission allowlist keyed by the Keycloak identity-provider alias. The alias is the
 * exact value the broker session note emits as the {@code memoryos_identity_provider} ID-token claim.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JitAllowlistRepository {

    private static final String INSERT = """
            INSERT INTO jit_allowed_provider (alias, created_by)
            VALUES (:alias, :createdBy)
            ON CONFLICT (alias) DO NOTHING
            """;
    private static final String DELETE = """
            DELETE FROM jit_allowed_provider
            WHERE alias = :alias
            """;
    private static final String EXISTS = """
            SELECT 1
            FROM jit_allowed_provider
            WHERE alias = :alias
            """;
    private static final String LIST = """
            SELECT alias
            FROM jit_allowed_provider
            """;

    private final JdbcClient jdbcClient;

    public JitAllowlistRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void allow(String alias, ActorId createdBy) {
        jdbcClient.sql(INSERT)
                .param("alias", requireAlias(alias))
                .param("createdBy", createdBy == null ? null : createdBy.value())
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void disallow(String alias) {
        jdbcClient.sql(DELETE).param("alias", requireAlias(alias)).update();
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(String alias) {
        return jdbcClient.sql(EXISTS)
                .param("alias", requireAlias(alias))
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Transactional(readOnly = true)
    public Set<String> allowedAliases() {
        return Set.copyOf(jdbcClient.sql(LIST).query(String.class).set());
    }

    private static String requireAlias(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        if (alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        return alias;
    }
}
