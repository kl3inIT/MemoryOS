package io.memoryos.identity.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcExternalIdentityResolver implements ExternalIdentityResolver {

    private static final String SELECT_ACTOR = """
            SELECT actor_id
            FROM external_identity_bindings
            WHERE issuer = :issuer AND subject = :subject
            """;

    private final JdbcClient jdbcClient;

    public JdbcExternalIdentityResolver(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<ActorId> resolve(ExternalIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        return jdbcClient
                .sql(SELECT_ACTOR)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .query(UUID.class)
                .optional()
                .map(ActorId::new);
    }

}
