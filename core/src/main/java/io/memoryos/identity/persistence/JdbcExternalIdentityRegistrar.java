package io.memoryos.identity.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcExternalIdentityRegistrar implements ExternalIdentityRegistrar {

    private static final String INSERT_ACTOR = """
            INSERT INTO actors (id)
            VALUES (:actorId)
            """;

    private static final String INSERT_BINDING = """
            INSERT INTO external_identity_bindings (issuer, subject, actor_id)
            VALUES (:issuer, :subject, :actorId)
            """;

    private final JdbcClient jdbcClient;
    private final ExternalIdentityResolver identityResolver;

    public JdbcExternalIdentityRegistrar(JdbcClient jdbcClient, ExternalIdentityResolver identityResolver) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActorId resolveOrCreate(ExternalIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        return identityResolver.resolve(identity).orElseGet(() -> create(identity));
    }

    private ActorId create(ExternalIdentity identity) {
        ActorId actorId = new ActorId(UUID.randomUUID());
        jdbcClient.sql(INSERT_ACTOR).param("actorId", actorId.value()).update();
        jdbcClient
                .sql(INSERT_BINDING)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .param("actorId", actorId.value())
                .update();
        return actorId;
    }
}
